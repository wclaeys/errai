/*
 * Copyright 2011 JBoss, by Red Hat, Inc
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

package org.jboss.errai.ioc.rebind.ioc.injector.api;

import static java.util.Collections.unmodifiableCollection;

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.enterprise.context.NormalScope;
import javax.enterprise.inject.Stereotype;
import javax.inject.Qualifier;
import javax.inject.Scope;

import org.jboss.errai.codegen.Statement;
import org.jboss.errai.codegen.meta.HasAnnotations;
import org.jboss.errai.codegen.meta.MetaClass;
import org.jboss.errai.codegen.meta.MetaClassFactory;
import org.jboss.errai.codegen.meta.MetaField;
import org.jboss.errai.codegen.meta.MetaMethod;
import org.jboss.errai.codegen.meta.MetaParameter;
import org.jboss.errai.codegen.util.PrivateAccessType;
import org.jboss.errai.codegen.util.PrivateAccessUtil;
import org.jboss.errai.common.client.api.Assert;
import org.jboss.errai.config.rebind.ReachableTypes;
import org.jboss.errai.ioc.rebind.ioc.bootstrapper.IOCGenerator;
import org.jboss.errai.ioc.rebind.ioc.bootstrapper.IOCProcessingContext;
import org.jboss.errai.ioc.rebind.ioc.exception.InjectionFailure;
import org.jboss.errai.ioc.rebind.ioc.extension.IOCDecoratorExtension;
import org.jboss.errai.ioc.rebind.ioc.graph.GraphBuilder;
import org.jboss.errai.ioc.rebind.ioc.injector.Injector;
import org.jboss.errai.ioc.rebind.ioc.injector.ProxyInjector;
import org.jboss.errai.ioc.rebind.ioc.injector.QualifiedTypeInjectorDelegate;
import org.jboss.errai.ioc.rebind.ioc.injector.TypeInjector;
import org.jboss.errai.ioc.rebind.ioc.metadata.QualifyingMetadata;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Multimap;

public class InjectionContext {
  private final IOCProcessingContext processingContext;

  private final Multimap<WiringElementType, Class<? extends Annotation>> elementBindings = HashMultimap.create();

  // do not refactor to a MultiMap. the resolution algorithm has dynamic replacement of injectors that is difficult
  // to achieve with a MultiMap
  private final Map<MetaClass, List<Injector>> injectors = new LinkedHashMap<MetaClass, List<Injector>>();

  private final Multimap<MetaClass, Injector> proxiedInjectors = LinkedHashMultimap.create();
  private final Multimap<MetaClass, MetaClass> cyclingTypes = HashMultimap.create();
  private final Set<String> knownTypesWithCycles = new HashSet<String>();
  private final ReachableTypes reachableTypes;

  private final Set<String> enabledAlternatives;

  private final Multimap<Class<? extends Annotation>, IOCDecoratorExtension> decorators = HashMultimap.create();
  private final Multimap<ElementType, Class<? extends Annotation>> decoratorsByElementType = HashMultimap.create();

  private final Set<Object> overridenTypesAndMembers = new HashSet<Object>();

  private final Map<MetaParameter, Statement> inlineBeanReferenceMap = new HashMap<MetaParameter, Statement>();

  private final Map<MetaField, PrivateAccessType> privateFieldsToExpose = new HashMap<MetaField, PrivateAccessType>();
  private final Collection<MetaMethod> privateMethodsToExpose = new LinkedHashSet<MetaMethod>();

  private final Map<String, Object> attributeMap = new HashMap<String, Object>();
  private final Set<String> exposedMembers = new HashSet<String>();

  private final Multimap<String, InjectorRegistrationListener> injectionRegistrationListener
      = HashMultimap.create();

  private final GraphBuilder graphBuilder = new GraphBuilder();

  private boolean allowProxyCapture = false;
  private boolean openProxy = false;

  private InjectionContext(final Builder builder) {
    this.processingContext = builder.processingContext;
    this.enabledAlternatives = Collections.unmodifiableSet(new HashSet<String>(builder.enabledAlternatives));
    this.reachableTypes = Assert.notNull(builder.reachableTypes);
  }

  public static class Builder {
    private IOCProcessingContext processingContext;
    private ReachableTypes reachableTypes = ReachableTypes.EVERYTHING_REACHABLE_INSTANCE;
    private final HashSet<String> enabledAlternatives = new HashSet<String>();

    public static Builder create() {
      return new Builder();
    }

    public Builder processingContext(final IOCProcessingContext processingContext) {
      this.processingContext = processingContext;
      return this;
    }

    public Builder enabledAlternative(final String fqcn) {
      enabledAlternatives.add(fqcn);
      return this;
    }

    public Builder reachableTypes(final ReachableTypes reachableTypes) {
      this.reachableTypes = reachableTypes;
      return this;
    }


    public InjectionContext build() {
      Assert.notNull("the processingContext cannot be null", processingContext);

      return new InjectionContext(this);
    }
  }

  public Injector getProxiedInjector(final MetaClass type, final QualifyingMetadata metadata) {
    //todo: figure out why I was doing this.
    final MetaClass erased = type.getErased();
    final Collection<Injector> injs = proxiedInjectors.get(erased);
    final List<Injector> matching = new ArrayList<Injector>();

    if (injs != null) {
      for (final Injector inj : injs) {
        if (inj.matches(type.getParameterizedType(), metadata)) {
          matching.add(inj);
        }
      }
    }

    if (matching.isEmpty()) {
      throw new InjectionFailure(erased);
    }
    else {
      // proxies can only be used once, so just receive the last declared one.
      return matching.get(matching.size() - 1);
    }
  }

  public Injector getQualifiedInjector(final MetaClass type, final QualifyingMetadata metadata) {
    final MetaClass erased = type.getErased();
    final List<Injector> injectors = this.injectors.get(erased);
    final List<Injector> matching = new ArrayList<Injector>();

    boolean alternativeBeans = false;

    if (injectors != null) {
      for (final Injector inj : injectors) {
        if (inj.matches(type.getParameterizedType(), metadata)) {

          if (!inj.isEnabled()) {
            if (inj.isSoftDisabled()) {
              inj.setEnabled(true);
            }
            else {
              continue;
            }
          }

          matching.add(inj);
          if (inj.isAlternative()) {
            alternativeBeans = true;
          }
        }
      }
    }

    if (matching.size() > 1 && type.isConcrete()) {
      // perform second pass
      final Iterator<Injector> secondIter = matching.iterator();
      while (secondIter.hasNext()) {
        if (!secondIter.next().getInjectedType().equals(erased))
          secondIter.remove();
      }
    }

    if (matching.isEmpty()) {
      throw new InjectionFailure(erased);
    }
    else if (matching.size() > 1) {
      if (alternativeBeans) {
        final Iterator<Injector> matchIter = matching.iterator();
        while (matchIter.hasNext()) {
          if (!enabledAlternatives.contains(matchIter.next().getInjectedType().getFullyQualifiedName())) {
            matchIter.remove();
          }
        }
      }

      if (IOCGenerator.isTestMode) {
        final List<Injector> matchingMocks = new ArrayList<Injector>();
        for (final Injector inj : matching) {
          if (inj.isTestMock()) {
            matchingMocks.add(inj);
          }
        }

        if (!matchingMocks.isEmpty()) {
          matching.clear();
          matching.addAll(matchingMocks);
        }
      }

      if (matching.isEmpty()) {
        throw new InjectionFailure(erased);
      }
      if (matching.size() == 1) {
        return matching.get(0);
      }

      final StringBuilder buf = new StringBuilder();
      for (final Injector inj : matching) {
        buf.append("     matching> ").append(inj.toString()).append("\n");
      }

      buf.append("  Note: configure an alternative to take precedence or remove all but one matching bean.");

      throw new InjectionFailure("ambiguous injection type (multiple injectors resolved): "
          + erased.getFullyQualifiedName() + " " + (metadata == null ? "" : metadata.toString()) + ":\n" +
          buf.toString());
    }
    else {
      return matching.get(0);
    }
  }

  public boolean hasInjectorForType(final MetaClass type) {
    final List<Injector> injectorList = injectors.get(type);
    return injectorList != null && !injectorList.isEmpty();
  }

  public boolean isTypeInjectable(final MetaClass type) {
    final List<Injector> injectorList = injectors.get(type);
    if (injectorList != null) {
      for (final Injector injector : injectorList) {
        if (!injector.isRendered()) {
          return false;
        }
      }

      return true;
    }
    else {
      return false;
    }
  }

  public void recordCycle(final MetaClass from, final MetaClass to) {
    cyclingTypes.put(from, to);
  }

  public boolean cycles(final MetaClass from, final MetaClass to) {
    return cyclingTypes.containsEntry(from, to);
  }

  public void addProxiedInjector(final ProxyInjector proxyInjector) {
    proxiedInjectors.put(proxyInjector.getInjectedType(), proxyInjector);
  }

  public void markProxyClosedIfNeeded(final MetaClass injectorType, final QualifyingMetadata qualifyingMetadata) {
    if (proxiedInjectors.containsKey(injectorType.getErased())) {
      final Collection<Injector> collection = proxiedInjectors.get(injectorType.getErased());
      final Iterator<Injector> iterator = collection.iterator();
      while (iterator.hasNext()) {
        if (iterator.next().matches(injectorType.getParameterizedType(), qualifyingMetadata)) {
          iterator.remove();
        }
      }
      if (collection.isEmpty()) {
        proxiedInjectors.removeAll(injectorType.getErased());
      }
    }
  }

  public boolean isProxiedInjectorRegistered(final MetaClass injectorType, final QualifyingMetadata qualifyingMetadata) {
    if (proxiedInjectors.containsKey(injectorType.getErased())) {
      for (final Injector inj : proxiedInjectors.get(injectorType.getErased())) {
        if (inj.matches(injectorType.getParameterizedType(), qualifyingMetadata)) {
          return true;
        }
      }
    }
    return false;
  }

  public boolean isInjectorRegistered(final MetaClass injectorType, final QualifyingMetadata qualifyingMetadata) {
    if (injectors.containsKey(injectorType.getErased())) {
      for (final Injector inj : injectors.get(injectorType.getErased())) {
        if (inj.matches(injectorType.getParameterizedType(), qualifyingMetadata)) {
          return true;
        }
      }
    }
    return false;
  }

  public boolean isInjectableQualified(final MetaClass injectorType, final QualifyingMetadata qualifyingMetadata) {
    if (injectors.containsKey(injectorType.getErased())) {
      for (final Injector inj : injectors.get(injectorType.getErased())) {
        if (inj.matches(injectorType.getParameterizedType(), qualifyingMetadata)) {
          return inj.isRendered();
        }
      }
    }
    return false;
  }

  public List<Injector> getInjectors(final MetaClass type) {
    List<Injector> injectorList = injectors.get(type);
    if (injectorList == null) {
      injectorList = Collections.emptyList();
    }
    return Collections.unmodifiableList(injectorList);
  }

  public Injector getInjector(final Class<?> injectorType) {
    return getInjector(MetaClassFactory.get(injectorType));
  }

  public Injector getInjector(final MetaClass type) {
    final MetaClass erased = type.getErased();
    if (!injectors.containsKey(erased)) {
      throw new InjectionFailure("could not resolve type for injection: " + erased.getFullyQualifiedName());
    }

    final List<Injector> injectorList = new ArrayList<Injector>(injectors.get(erased));
    final Iterator<Injector> iter = injectorList.iterator();
    Injector inj;

    if (injectorList.size() > 1) {
      while (iter.hasNext()) {
        inj = iter.next();

        if (type.getParameterizedType() != null) {
          if (inj.getQualifyingTypeInformation() != null) {
            if (!type.getParameterizedType().isAssignableFrom(inj.getQualifyingTypeInformation())) {
              iter.remove();
            }
          }
        }

        if (!inj.isEnabled()) {
          iter.remove();
        }
      }
    }

    if (injectorList.size() > 1) {
      // perform second pass
      final Iterator<Injector> secondIter = injectorList.iterator();
      while (secondIter.hasNext()) {
        if (!secondIter.next().getInjectedType().equals(erased))
          secondIter.remove();
      }
    }

    if (injectorList.size() > 1) {
      throw new InjectionFailure("ambiguous injection type (multiple injectors resolved): "
          + erased.getFullyQualifiedName());
    }
    if (injectorList.isEmpty()) {
      throw new InjectionFailure("could not resolve type for injection: " + erased.getFullyQualifiedName());
    }

    return injectorList.get(0);
  }

  public void registerInjector(final Injector injector) {
    registerInjector(injector.getInjectedType(), injector, new HashSet<MetaClass>(), true);
  }

  private void registerInjector(final MetaClass type, final Injector injector, final Set<MetaClass> processedTypes, final boolean allowOverride) {
    List<Injector> injectorList = injectors.get(type.getErased());

    if (injectorList == null) {
      injectors.put(type.getErased(), injectorList = new ArrayList<Injector>());
    }
    else if (allowOverride) {
      final Iterator<Injector> iter = injectorList.iterator();

      while (iter.hasNext()) {
        final Injector inj = iter.next();

        if (inj.isPseudo()) {
          iter.remove();
        }
      }
    }

    registerInjectorsForSuperTypesAndInterfaces(type, injector, processedTypes);
    injectorList.add(injector);

    notifyInjectorRegistered(injector);
  }

  private void registerInjectorsForSuperTypesAndInterfaces(final MetaClass type, final Injector injector,
                                                           final Set<MetaClass> processedTypes) {
    MetaClass cls = type;
    do {
      if (cls != type && cls.isPublic()) {
        if (processedTypes.add(cls)) {
          final QualifiedTypeInjectorDelegate injectorDelegate =
              new QualifiedTypeInjectorDelegate(cls, injector, cls.getParameterizedType());

          registerInjector(cls, injectorDelegate, processedTypes, false);
        }
        continue;
      }

      for (final MetaClass iface : cls.getInterfaces()) {
        if (!iface.isPublic())
          continue;

        if (processedTypes.add(iface)) {
          final QualifiedTypeInjectorDelegate injectorDelegate =
              new QualifiedTypeInjectorDelegate(iface, injector, iface.getParameterizedType());

          registerInjector(iface, injectorDelegate, processedTypes, false);
        }
      }
    }
    while ((cls = cls.getSuperClass()) != null && !cls.getFullyQualifiedName().equals("java.lang.Object"));
  }

  public void registerDecorator(final IOCDecoratorExtension<?> iocExtension) {
    decorators.get(iocExtension.decoratesWith()).add(iocExtension);
  }

  public Set<Class<? extends Annotation>> getDecoratorAnnotations() {
    return Collections.unmodifiableSet(decorators.keySet());
  }

  public IOCDecoratorExtension[] getDecorator(final Class<? extends Annotation> annotation) {
    final Collection<IOCDecoratorExtension> decs = decorators.get(annotation);
    final IOCDecoratorExtension[] da = new IOCDecoratorExtension[decs.size()];
    decs.toArray(da);
    return da;
  }

  public Collection<Class<? extends Annotation>> getDecoratorAnnotationsBy(final ElementType type) {
    if (decoratorsByElementType.size() == 0) {
      sortDecorators();
    }
    if (decoratorsByElementType.containsKey(type)) {
      return unmodifiableCollection(decoratorsByElementType.get(type));
    }
    else {
      return Collections.emptySet();
    }
  }

  private void sortDecorators() {
    for (final Class<? extends Annotation> a : getDecoratorAnnotations()) {
      if (a.isAnnotationPresent(Target.class)) {
        for (final ElementType type : a.getAnnotation(Target.class).value()) {
          decoratorsByElementType.get(type).add(a);
        }
      }
    }
  }

  public void addExposedField(final MetaField field, PrivateAccessType accessType) {
    if (!privateFieldsToExpose.containsKey(field)) {
      privateFieldsToExpose.put(field, accessType);
    }
    else if (privateFieldsToExpose.get(field) != accessType) {
      accessType = PrivateAccessType.Both;
    }
    privateFieldsToExpose.put(field, accessType);
  }

  public void addExposedMethod(final MetaMethod method) {
    final String methodSignature = PrivateAccessUtil.getPrivateMethodName(method);
    if (!exposedMembers.contains(methodSignature)) {
      exposedMembers.add(methodSignature);
    }
    else {
      return;
    }
    privateMethodsToExpose.add(method);
  }

  public void declareOverridden(final MetaClass type) {
    overridenTypesAndMembers.add(type);
  }

  public void declareOverridden(final MetaMethod method) {
    overridenTypesAndMembers.add(method);
  }

  public boolean isOverridden(final MetaClass type) {
    return overridenTypesAndMembers.contains(type);
  }

  public boolean isOverridden(final MetaMethod method) {
    return overridenTypesAndMembers.contains(method);
  }

  public Map<MetaField, PrivateAccessType> getPrivateFieldsToExpose() {
    return Collections.unmodifiableMap(privateFieldsToExpose);
  }

  public Collection<MetaMethod> getPrivateMethodsToExpose() {
    return unmodifiableCollection(privateMethodsToExpose);
  }

  public void addType(final MetaClass type) {
    if (injectors.containsKey(type))
      return;
    registerInjector(new TypeInjector(type, this));
  }

  public void addPsuedoScopeForType(final MetaClass type) {
    final TypeInjector inj = new TypeInjector(type, this);
    inj.setReplaceable(true);
    registerInjector(inj);
  }

  public IOCProcessingContext getProcessingContext() {
    return processingContext;
  }

  public void addEnabledAlternative(final String name) {
    enabledAlternatives.add(name);
  }

  public void mapElementType(final WiringElementType type, final Class<? extends Annotation> annotationType) {
    elementBindings.put(type, annotationType);
  }

  public Collection<Class<? extends Annotation>> getAnnotationsForElementType(final WiringElementType type) {
    return unmodifiableCollection(elementBindings.get(type));
  }

  public boolean isAnyKnownElementType(final HasAnnotations hasAnnotations) {
    return isAnyOfElementTypes(hasAnnotations, WiringElementType.values());
  }

  public boolean isAnyOfElementTypes(final HasAnnotations hasAnnotations, final WiringElementType... types) {
    for (final WiringElementType t : types) {
      if (isElementType(t, hasAnnotations))
        return true;
    }
    return false;
  }

  public boolean isElementType(final WiringElementType type, final HasAnnotations hasAnnotations) {
    return getMatchingAnnotationForElementType(type, hasAnnotations) != null;
  }

  public boolean isElementType(final WiringElementType type, final Class<? extends Annotation> annotation) {
    return getAnnotationsForElementType(type).contains(annotation);
  }


  /**
   * Overloaded version to check GWT's JClassType classes.
   *
   * @param type
   * @param hasAnnotations
   *
   * @return
   */
  public boolean isElementType(final WiringElementType type, final com.google.gwt.core.ext.typeinfo.HasAnnotations hasAnnotations) {
    final Collection<Class<? extends Annotation>> annotationsForElementType = getAnnotationsForElementType(type);
    for (final Annotation a : hasAnnotations.getAnnotations()) {
      if (annotationsForElementType.contains(a.annotationType())) {
        return true;
      }
    }
    return false;
  }

  public Annotation getMatchingAnnotationForElementType(final WiringElementType type, final HasAnnotations hasAnnotations) {
    final Collection<Class<? extends Annotation>> annotationsForElementType = getAnnotationsForElementType(type);
    final Set<Annotation> annotationSet
        = new HashSet<Annotation>(Arrays.asList(hasAnnotations.getAnnotations()));

    fillInStereotypes(annotationSet, hasAnnotations.getAnnotations(), false);

    for (final Annotation a : annotationSet) {
      if (annotationsForElementType.contains(a.annotationType())) {
        return a;
      }
    }
    return null;
  }

  private static void fillInStereotypes(final Set<Annotation> annotationSet,
                                        final Annotation[] from,
                                        boolean filterScopes) {

    final List<Class<? extends Annotation>> stereotypes
        = new ArrayList<Class<? extends Annotation>>();

    for (final Annotation a : from) {
      final Class<? extends Annotation> aClass = a.annotationType();
      if (aClass.isAnnotationPresent(Stereotype.class)) {
        stereotypes.add(aClass);
      }
      else if (!filterScopes &&
          aClass.isAnnotationPresent(NormalScope.class) ||
          aClass.isAnnotationPresent(Scope.class)) {
        filterScopes = true;

        annotationSet.add(a);
      }
      else if (aClass.isAnnotationPresent(Qualifier.class)) {
        annotationSet.add(a);
      }
    }

    for (final Class<? extends Annotation> stereotype : stereotypes) {
      fillInStereotypes(annotationSet, stereotype.getAnnotations(), filterScopes);
    }
  }

  public Collection<Map.Entry<WiringElementType, Class<? extends Annotation>>> getAllElementMappings() {
    return unmodifiableCollection(elementBindings.entries());
  }

  public Collection<MetaClass> getAllKnownInjectionTypes() {
    return unmodifiableCollection(injectors.keySet());
  }

  public void allowProxyCapture() {
    allowProxyCapture = true;
  }

  public void markOpenProxy() {
    if (allowProxyCapture) {
      openProxy = true;
    }
  }

  public boolean isProxyOpen() {
    return openProxy;
  }

  public void closeProxyIfOpen() {
    if (openProxy) {
      getProcessingContext().popBlockBuilder();
      openProxy = false;
    }
    allowProxyCapture = false;
  }

  public void addInjectorRegistrationListener(final MetaClass clazz, final InjectorRegistrationListener listener) {
    injectionRegistrationListener.put(clazz.getFullyQualifiedName(), listener);

    if (injectors.containsKey(clazz)) {
      final List<Injector> injectors = this.injectors.get(clazz);
      for (final Injector injector : injectors) {
        listener.onRegister(clazz, injector);
      }
    }
  }

  private void notifyInjectorRegistered(final Injector injector) {
    if (injectionRegistrationListener.containsKey(injector.getInjectedType().getFullyQualifiedName())) {
      final Collection<InjectorRegistrationListener> injectorRegistrationListeners
          = injectionRegistrationListener.get(injector.getInjectedType().getFullyQualifiedName());

      for (final InjectorRegistrationListener listener : injectorRegistrationListeners) {
        listener.onRegister(injector.getInjectedType(), injector);
      }
    }
  }

  public boolean isReachable(final MetaClass clazz) {
    return isReachable(clazz.getFullyQualifiedName());
  }

  public boolean isReachable(final String fqnc) {
    return reachableTypes.isEmpty() || reachableTypes.contains(fqnc);
  }

  public Collection<String> getAllReachableTypes() {
    return reachableTypes.toCollection();
  }

  public void setAttribute(final String name, final Object value) {
    attributeMap.put(name, value);
  }

  public Object getAttribute(final String name) {
    return attributeMap.get(name);
  }

  public boolean hasAttribute(final String name) {
    return attributeMap.containsKey(name);
  }

  public void addKnownTypesWithCycles(final Collection<String> types) {
    knownTypesWithCycles.addAll(types);
  }

  public boolean typeContainsGraphCycles(final MetaClass type) {
    return knownTypesWithCycles.contains(type.getFullyQualifiedName());
  }

  public void addInlineBeanReference(final MetaParameter ref, final Statement statement) {
    inlineBeanReferenceMap.put(ref, statement);
  }

  public Statement getInlineBeanReference(final MetaParameter ref) {
    return inlineBeanReferenceMap.get(ref);
  }

  public GraphBuilder getGraphBuilder() {
    return graphBuilder;
  }
}
