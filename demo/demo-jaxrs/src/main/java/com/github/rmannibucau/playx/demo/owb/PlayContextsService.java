package com.github.rmannibucau.playx.demo.owb;

import java.lang.annotation.Annotation;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.context.ContextException;
import javax.enterprise.context.Dependent;
import javax.enterprise.context.spi.Context;
import javax.inject.Singleton;

import org.apache.webbeans.annotation.BeforeDestroyedLiteral;
import org.apache.webbeans.annotation.DestroyedLiteral;
import org.apache.webbeans.annotation.InitializedLiteral;
import org.apache.webbeans.config.WebBeansContext;
import org.apache.webbeans.context.AbstractContextsService;
import org.apache.webbeans.context.ApplicationContext;
import org.apache.webbeans.context.DependentContext;
import org.apache.webbeans.context.SingletonContext;

// workaround, will be fixed in OWB
public class PlayContextsService extends AbstractContextsService {

    public PlayContextsService(final WebBeansContext webBeansContext) {
        super(webBeansContext);
    }

    private ApplicationContext applicationContext;

    private SingletonContext singletonContext;

    private static ThreadLocal<DependentContext> dependentContext;

    static {
        dependentContext = new ThreadLocal<>();
    }

    @Override
    public void endContext(Class<? extends Annotation> scopeType, Object endParameters) {

        if (scopeType.equals(ApplicationScoped.class)) {
            stopApplicationContext();
        } else if (scopeType.equals(Singleton.class)) {
            stopSingletonContext();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Context getCurrentContext(Class<? extends Annotation> scopeType) {
        if (scopeType.equals(ApplicationScoped.class)) {
            return applicationContext;
        } else if (scopeType.equals(Dependent.class)) {
            return getCurrentDependentContext();
        } else if (scopeType.equals(Singleton.class)) {
            return singletonContext;
        }

        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void startContext(Class<? extends Annotation> scopeType, Object startParameter) throws ContextException {
        try {
            if (scopeType.equals(ApplicationScoped.class)) {
                startApplicationContext();
            } else if (scopeType.equals(Singleton.class)) {
                startSingletonContext();
            }

            // do nothing for Dependent.class

        } catch (final ContextException ce) {
            throw ce;
        } catch (final Exception e) {
            throw new ContextException(e);
        }
    }

    @Override
    public void destroy(Object destroyObject) {
        if (singletonContext != null) {
            singletonContext.destroy();
        }

        dependentContext.set(null);
        dependentContext.remove();

        if (applicationContext != null) {
            applicationContext.destroy();
            applicationContext.destroySystemBeans();
        }
    }

    private Context getCurrentDependentContext() {
        if (dependentContext.get() == null) {
            dependentContext.set(new DependentContext());
        }

        return dependentContext.get();
    }

    private void startApplicationContext() {
        if (applicationContext != null && !applicationContext.isDestroyed()) {
            // applicationContext is already started
            return;
        }

        ApplicationContext ctx = new ApplicationContext();
        ctx.setActive(true);

        applicationContext = ctx;
        webBeansContext.getBeanManagerImpl().fireContextLifecyleEvent(new Object(),
                InitializedLiteral.INSTANCE_APPLICATION_SCOPED);
    }

    private void startSingletonContext() {
        singletonContext = new SingletonContext();
        singletonContext.setActive(true);
        webBeansContext.getBeanManagerImpl().fireContextLifecyleEvent(new Object(), InitializedLiteral.INSTANCE_SINGLETON_SCOPED);
    }

    private void stopApplicationContext() {
        if (applicationContext != null && !applicationContext.isDestroyed()) {
            webBeansContext.getBeanManagerImpl().fireContextLifecyleEvent(new Object(),
                    BeforeDestroyedLiteral.INSTANCE_APPLICATION_SCOPED);

            applicationContext.destroy();

            // this is needed to get rid of ApplicationScoped beans which are cached inside the proxies...
            WebBeansContext.currentInstance().getBeanManagerImpl().clearCacheProxies();
            webBeansContext.getBeanManagerImpl().fireContextLifecyleEvent(new Object(),
                    DestroyedLiteral.INSTANCE_APPLICATION_SCOPED);
        }
    }

    private void stopSingletonContext() {
        webBeansContext.getBeanManagerImpl().fireContextLifecyleEvent(new Object(),
                BeforeDestroyedLiteral.INSTANCE_SINGLETON_SCOPED);
        if (singletonContext != null) {
            singletonContext.destroy();
        }

        webBeansContext.getBeanManagerImpl().fireContextLifecyleEvent(new Object(), DestroyedLiteral.INSTANCE_SINGLETON_SCOPED);
    }
}
