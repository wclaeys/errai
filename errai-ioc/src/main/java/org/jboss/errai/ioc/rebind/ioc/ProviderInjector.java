package org.jboss.errai.ioc.rebind.ioc;

import com.google.gwt.core.ext.typeinfo.JClassType;

public class ProviderInjector extends TypeInjector {
    private final Injector providerInjector;

    public ProviderInjector(JClassType type, JClassType providerType) {
        super(type);
        this.providerInjector = new TypeInjector(providerType);
    }

    @Override
    public String getType(InjectionContext injectContext) {
        injected = true;
        return providerInjector.getType(injectContext) + ".provide()";
    }

    @Override
    public String instantiateOnly(InjectionContext injectContext) {
        injected = true;
        return providerInjector.getType(injectContext);
    }
}