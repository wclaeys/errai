/*
 * Copyright 2009 JBoss, a divison Red Hat, Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.errai.bus.server.service.bootstrap;

import com.google.inject.AbstractModule;
import com.google.inject.CreationException;
import com.google.inject.Guice;
import com.google.inject.Injector;
import org.jboss.errai.bus.client.api.MessageCallback;
import org.jboss.errai.bus.client.api.ResourceProvider;
import org.jboss.errai.bus.client.api.builder.AbstractRemoteCallBuilder;
import org.jboss.errai.bus.client.framework.MessageBus;
import org.jboss.errai.bus.client.framework.ProxyProvider;
import org.jboss.errai.bus.client.framework.RequestDispatcher;
import org.jboss.errai.bus.rebind.RebindUtils;
import org.jboss.errai.bus.server.ErraiBootstrapFailure;
import org.jboss.errai.bus.server.annotations.*;
import org.jboss.errai.bus.server.annotations.security.RequireAuthentication;
import org.jboss.errai.bus.server.annotations.security.RequireRoles;
import org.jboss.errai.bus.server.api.ErraiConfig;
import org.jboss.errai.bus.server.api.ErraiConfigExtension;
import org.jboss.errai.bus.server.api.Module;
import org.jboss.errai.bus.server.io.ConversationalEndpointCallback;
import org.jboss.errai.bus.server.io.EndpointCallback;
import org.jboss.errai.bus.server.io.RemoteServiceCallback;
import org.jboss.errai.bus.server.security.auth.rules.RolesRequiredRule;
import org.jboss.errai.bus.server.service.ErraiServiceConfigurator;
import org.jboss.errai.bus.server.service.ErraiServiceConfiguratorImpl;
import org.jboss.errai.bus.server.util.ConfigUtil;
import org.jboss.errai.bus.server.util.ConfigVisitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.lang.reflect.Method;
import java.util.*;

import static org.jboss.errai.bus.server.util.ConfigUtil.visitAllTargets;

/**
 * Parses the annotation meta data and configures both services and extensions.
 * 
 * @author: Heiko Braun <hbraun@redhat.com>
 * @date: May 3, 2010
 */
class ScanModules implements BootstrapExecution
{
  private Logger log = LoggerFactory.getLogger(ScanModules.class);

  public void execute(final BootstrapContext context)
  {
    final ErraiServiceConfiguratorImpl config = (ErraiServiceConfiguratorImpl)context.getConfig();

    log.info("beging searching for Errai extensions ...");    
    boolean autoScanModules = true;
    
    final Set<String> loadedComponents = new HashSet<String>();

    /*** Extensions  ***/
    if (config.hasProperty("errai.auto_scan_modules")) {
      autoScanModules = Boolean.parseBoolean(config.getProperty("errai.auto_scan_modules"));
    }
    if (autoScanModules) {

      final ErraiConfig erraiConfig = new ErraiConfig() {
        public void addBinding(Class<?> type, ResourceProvider provider) {
          config.getExtensionBindings().put(type, provider);
        }

        public void addResourceProvider(String name, ResourceProvider provider) {
          config.getResourceProviders().put(name, provider);
        }

        public void addSerializableType(Class<?> type) {
          log.info("Marked " + type + " as serializable.");
          loadedComponents.add(type.getName());
          config.getSerializableTypes().add(type);
        }
      };

      // Search for Errai extensions.
      List<File> configRootTargets = ConfigUtil.findAllConfigTargets();
      visitAllTargets(configRootTargets, new ConfigVisitor() {
        public void visit(Class<?> loadClass) {
          if (ErraiConfigExtension.class.isAssignableFrom(loadClass)
              && loadClass.isAnnotationPresent(ExtensionComponent.class) && !loadedComponents.contains(loadClass.getName())) {

            loadedComponents.add(loadClass.getName());

            // We have an annotated ErraiConfigExtension.  So let's configure it.
            final Class<? extends ErraiConfigExtension> clazz =
                loadClass.asSubclass(ErraiConfigExtension.class);


            log.info("found extension " + clazz.getName());

            try {

              final Runnable create = new Runnable() {
                public void run() {
                  AbstractModule module = new AbstractModule() {
                    @Override
                    protected void configure() {
                      bind(ErraiConfigExtension.class).to(clazz);
                      bind(ErraiServiceConfigurator.class).toInstance(config);
                      bind(MessageBus.class).toInstance(context.getBus());

                      // Add any extension bindings.
                      for (Map.Entry<Class<?>, ResourceProvider> entry : config.getExtensionBindings().entrySet()) {
                        bind(entry.getKey()).toProvider(new GuiceProviderProxy(entry.getValue()));
                      }
                    }
                  };
                  Guice.createInjector(module)
                      .getInstance(ErraiConfigExtension.class)
                      .configure(erraiConfig);
                }
              };

              try {
                create.run();
              }
              catch (CreationException e) {
                log.info("extension " + clazz.getName() + " cannot be bound yet, deferring ...");
                context.deferr(create);
              }

            }
            catch (Throwable e) {
              throw new ErraiBootstrapFailure("could not initialize extension: " + loadClass.getName(), e);
            }
          }
        }
      });


      for (Class bindingType : config.getExtensionBindings().keySet()) {
        log.info("added extension binding: " + bindingType.getName());
      }

      log.info("total extension binding: " + config.getExtensionBindings().keySet().size());


      visitAllTargets(configRootTargets,
          new ConfigVisitor() {
            public void visit(final Class<?> loadClass) {
              if (loadedComponents.contains(loadClass.getName())) return;


              if (Module.class.isAssignableFrom(loadClass)) {
                final Class<? extends Module> clazz = loadClass.asSubclass(Module.class);

                loadedComponents.add(loadClass.getName());

                if (clazz.isAnnotationPresent(LoadModule.class)) {
                  log.info("discovered module : " + clazz.getName() + " -- don't use Modules! Use @Service and MessageCallback!");
                  Guice.createInjector(new AbstractModule() {
                    @Override
                    protected void configure() {
                      bind(Module.class).to(clazz);
                      bind(MessageBus.class).toInstance(context.getBus());
                    }
                  }).getInstance(Module.class).init();
                }

              } else if (loadClass.isAnnotationPresent(Service.class)) {
                Object svc = null;

                Class remoteImpl = getRemoteImplementation(loadClass);
                if (remoteImpl != null) {
                  createRPCScaffolding(remoteImpl, loadClass, context);
                } else if (MessageCallback.class.isAssignableFrom(loadClass)) {
                  final Class<? extends MessageCallback> clazz = loadClass.asSubclass(MessageCallback.class);

                  loadedComponents.add(loadClass.getName());

                  log.info("discovered service: " + clazz.getName());
                  try {
                    svc = Guice.createInjector(new AbstractModule() {
                      @Override
                      protected void configure() {
                        bind(MessageCallback.class).to(clazz);
                        bind(MessageBus.class).toInstance(context.getBus());
                        bind(RequestDispatcher.class).toInstance(context.getService().getDispatcher());

                        // Add any extension bindings.
                        for (Map.Entry<Class<?>, ResourceProvider> entry : config.getExtensionBindings().entrySet()) {
                          bind(entry.getKey()).toProvider(new GuiceProviderProxy(entry.getValue()));
                        }
                      }
                    }).getInstance(MessageCallback.class);
                  }
                  catch (Throwable t) {
                    t.printStackTrace();
                  }


                  String svcName = clazz.getAnnotation(Service.class).value();

                  // If no name is specified, just use the class name as the service
                  // by default.
                  if ("".equals(svcName)) {
                    svcName = clazz.getSimpleName();
                  }

                  // Subscribe the service to the bus.
                  context.getBus().subscribe(svcName, (MessageCallback) svc);

                  RolesRequiredRule rule = null;
                  if (clazz.isAnnotationPresent(RequireRoles.class)) {
                    rule = new RolesRequiredRule(clazz.getAnnotation(RequireRoles.class).value(), context.getBus());
                  } else if (clazz.isAnnotationPresent(RequireAuthentication.class)) {
                    rule = new RolesRequiredRule(new HashSet<Object>(), context.getBus());
                  }
                  if (rule != null) {
                    context.getBus().addRule(svcName, rule);
                  }
                }

                if (svc == null) {
                  svc = Guice.createInjector(new AbstractModule() {
                    @Override
                    protected void configure() {
                      bind(MessageBus.class).toInstance(context.getBus());
                      bind(RequestDispatcher.class).toInstance(context.getService().getDispatcher());

                      // Add any extension bindings.
                      for (Map.Entry<Class<?>, ResourceProvider> entry : config.getExtensionBindings().entrySet()) {
                        bind(entry.getKey()).toProvider(new GuiceProviderProxy(entry.getValue()));
                      }
                    }
                  }).getInstance(loadClass);
                }

                Map<String, MessageCallback> epts = new HashMap<String, MessageCallback>();


                // we scan for endpoints
                for (final Method method : loadClass.getDeclaredMethods()) {
                  if (method.isAnnotationPresent(Endpoint.class)) {
                    epts.put(method.getName(), method.getReturnType() == Void.class ?
                        new EndpointCallback(svc, method) :
                        new ConversationalEndpointCallback(svc, method, context.getBus()));
                  }
                }

                if (!epts.isEmpty()) {
                  context.getBus().subscribe(loadClass.getSimpleName() + ":RPC", new RemoteServiceCallback(epts));
                }

              } else if (loadClass.isAnnotationPresent(ExposeEntity.class)) {
                log.info("Marked " + loadClass + " as serializable.");
                loadedComponents.add(loadClass.getName());
                config.getSerializableTypes().add(loadClass);
              }
            }
          }
      );
    } else {
      log.info("auto-scan disabled.");
    }

     try {
      ResourceBundle bundle = ResourceBundle.getBundle("ErraiApp");
      if (bundle != null) {
        log.info("checking ErraiApp.properties for configured types ...");

        if (bundle.containsKey(ErraiServiceConfigurator.CONFIG_ERRAI_SERIALIZABLE_TYPE)){
          for (String s : bundle.getString(ErraiServiceConfigurator.CONFIG_ERRAI_SERIALIZABLE_TYPE).split(" ")) {
            try {
              Class<?> cls = Class.forName(s.trim());
              log.info("Marked " + cls + " as serializable.");
              loadedComponents.add(cls.getName());
              config.getSerializableTypes().add(cls);
            }
            catch (Exception e) {
              throw new ErraiBootstrapFailure(e);
            }

          }
        }
      }
    }
    catch (MissingResourceException e) {
      throw new ErraiBootstrapFailure("unable to find ErraiApp.properties in the classpath.");
    }
  }

  private static Class getRemoteImplementation(Class type) {
    for (Class iface : type.getInterfaces()) {
      if (iface.isAnnotationPresent(Remote.class)) {
        return iface;
      } else if (iface.getInterfaces().length != 0 && ((iface = getRemoteImplementation(iface)) != null)) {
        return iface;
      }
    }
    return null;
  }

  private void createRPCScaffolding(final Class remoteIface, final Class<?> type, final BootstrapContext context) {

    final ErraiServiceConfiguratorImpl config = (ErraiServiceConfiguratorImpl)context.getConfig();
    final Injector injector = Guice.createInjector(new AbstractModule() {
      @Override
      protected void configure() {
        bind(MessageBus.class).toInstance(context.getBus());
        bind(RequestDispatcher.class).toInstance(context.getService().getDispatcher());

        // Add any extension bindings.
        for (Map.Entry<Class<?>, ResourceProvider> entry : config.getExtensionBindings().entrySet()) {
          bind(entry.getKey()).toProvider(new GuiceProviderProxy(entry.getValue()));
        }
      }
    });

    Object svc = injector.getInstance(type);

    Map<String, MessageCallback> epts = new HashMap<String, MessageCallback>();

    // beware of classloading issues. better reflect on the actual instance
    for (Class<?> intf : svc.getClass().getInterfaces()) {
      for (final Method method : intf.getDeclaredMethods()) {
        if (RebindUtils.isMethodInInterface(remoteIface, method)) {
          epts.put(RebindUtils.createCallSignature(method), new ConversationalEndpointCallback(svc, method, context.getBus()));
        }
      }
    }

    context.getBus().subscribe(remoteIface.getName() + ":RPC", new RemoteServiceCallback(epts));

    new ProxyProvider() {
      {
        AbstractRemoteCallBuilder.setProxyFactory(this);
      }

      public <T> T getRemoteProxy(Class<T> proxyType) {
        throw new RuntimeException("This API is not supported in the server-side environment.");
      }
    };
 
  }

}