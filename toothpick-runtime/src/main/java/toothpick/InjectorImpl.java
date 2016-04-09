package toothpick;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.inject.Provider;
import toothpick.config.Binding;
import toothpick.config.Module;
import toothpick.registries.factory.FactoryRegistryLocator;
import toothpick.registries.memberinjector.MemberInjectorRegistryLocator;

import static java.lang.String.format;

/**
 * This class should never be used outside of the toothpick library.
 */
public final class InjectorImpl implements Injector {
  public static final ExecutorService EXECUTOR_SERVICE = Executors.newFixedThreadPool(4);
  private IdentityHashMap<Class, Provider> scope = new IdentityHashMap<>();
  private Injector parent;
  //parentInjectors, sorted from this the root injector
  private final List<InjectorImpl> parentInjectors;
  private boolean hasOverrides;

  public InjectorImpl(Injector parent, Module... modules) {
    this.parent = parent;
    if (parent == null) {
      parentInjectors = new ArrayList<>();
    } else {
      parentInjectors = new ArrayList<>(((InjectorImpl) parent).parentInjectors);
    }
    parentInjectors.add(0, this);
    installModules(modules);
  }

  public InjectorImpl(Module... modules) {
    this(null, modules);
  }

  @Override
  public Injector getParent() {
    return parent;
  }

  @Override
  public <T> void inject(T obj) {
    MemberInjector<T> memberInjector = MemberInjectorRegistryLocator.getMemberInjector((Class<T>) obj.getClass());
    memberInjector.inject(obj, this);
  }

  public IdentityHashMap<Class, Provider> getScope() {
    return scope;
  }

  @Override
  public <T> T getInstance(Class<T> clazz) {
    return getProvider(clazz).get();
  }

  private InjectorImpl getRootInjector() {
    return parentInjectors.get(parentInjectors.size() - 1);
  }

  @Override
  public <T> Provider<T> getProvider(Class<T> clazz) {
    if (clazz == null) {
      throw new IllegalArgumentException("TP can't get an instance of a null class.");
    }
    synchronized (clazz) {
      Iterator<InjectorImpl> iterator = parentInjectors.iterator();
      while (iterator.hasNext()) {
        InjectorImpl parentInjector = iterator.next();
        Provider<T> scopedProvider = parentInjector.getScopedProvider(clazz);
        if (scopedProvider != null) {
          return scopedProvider;
        }
      }
    }
    //classes discovered at runtime, not bound by any module
    Factory<T> factory = FactoryRegistryLocator.getFactory(clazz);
    T instance = factory.createInstance(this);
    final Provider<T> newProvider;
    synchronized (clazz) {
      if (factory.hasSingletonAnnotation()) {
        //singleton classes discovered dynamically go to root scope.
        newProvider = new ProviderImpl<T>(instance);
        getRootInjector().getScope().put(clazz, newProvider);
      } else {
        newProvider = new ProviderImpl(this, factory, false);
        scope.put(clazz, newProvider);
      }
    }
    return newProvider;
  }

  //TODO explain the change to daniel, we were having some troubles
  //we could not be using a provider in multi-thread, it has to be
  //thread safe. The best was a multi DCL.
  //now all access to the injector scopes are locked on the class
  //all provider are locked on themselves.
  //I won't do unit tests, because they are too hard and don't bring much
  //TODO create a plugin system so that we can inject anything (handler/plugin ?)
  //Kill the 2 methods below
  @Override
  public <T> Lazy<T> getLazy(Class<T> clazz) {
    Provider<T> provider = getProvider(clazz);
    return new ProviderImpl<>(provider, true);
  }

  @Override
  public <T> Future<T> getFuture(Class<T> clazz) {
    final Provider<T> provider = getProvider(clazz);
    return EXECUTOR_SERVICE.submit(new ProviderCallable<>(provider));
  }

  @Override
  public void installOverrideModules(Module... modules) {
    //we allow multiple calls to this method
    boolean oldHasOverrides = hasOverrides;
    hasOverrides = false;
    installModules(modules);
    boolean doOverrideModulesExist = modules != null;
    hasOverrides = oldHasOverrides || doOverrideModulesExist;
  }

  private void installModule(Module module) {
    for (Binding binding : module.getBindingSet()) {
      if (binding == null) {
        throw new IllegalStateException("A module can't have a null binding.");
      }
      Class key = binding.getKey();
      synchronized (key) {
        if (!hasOverrides || !scope.containsKey(key)) {
          scope.put(key, toProvider(binding));
        }
      }
    }
  }

  /**
   * Obtains the provider of the class {@code clazz} that is scoped in the current scope, if any.
   * Ancestors are not taken into account.
   *
   * @param clazz the class for which to obtain the scoped provider of this injector, if one is scoped.
   * @param <T> the type of {@code clazz}.
   * @return the scoped provider of this injector, if one is scoped, {@code Null} otherwise.
   */
  private <T> Provider<T> getScopedProvider(Class<T> clazz) {
    return scope.get(clazz);
  }

  private void installModules(Module[] modules) {
    for (Module module : modules) {
      installModule(module);
    }
  }

  //do not change the return type to Provider<? extends T>.
  //it would be cool and more convenient for bindings, but it would
  //make the APIs very unstable as you could not get any instance of the
  //implementation class via an injector, it would fail but be syntactically valid.
  //only creating an instance of the interface is valid with this syntax.
  /*VisibleForTesting*/ <T> Provider<T> toProvider(Binding<T> binding) {
    if (binding == null) {
      throw new IllegalStateException("null binding are not allowed. Should not happen unless getBindingSet is overridden.");
    }
    switch (binding.getMode()) {
      case SIMPLE:
        Factory<? extends T> factory = FactoryRegistryLocator.getFactory(binding.getKey());
        return new ProviderImpl<>(this, factory, false);
      case CLASS:
        Factory<? extends T> factory2 = FactoryRegistryLocator.getFactory(binding.getImplementationClass());
        return new ProviderImpl<>(this, factory2, false);
      case INSTANCE:
        return new ProviderImpl<>(binding.getInstance());
      case PROVIDER_INSTANCE:
        //to ensure providers do not have to deal with concurrency, we wrap them in a thread safe provider
        return new ProviderImpl<>(binding.getProviderInstance(), false);
      case PROVIDER_CLASS:
        Factory<? extends Provider<T>> providerFactory = FactoryRegistryLocator.getFactory(binding.getProviderClass());
        return new ProviderImpl<>(this, providerFactory, true);

      //JACOCO:OFF
      default:
        throw new IllegalStateException(format("mode is not handled: %s. This should not happen.", binding.getMode()));
        //JACOCO:ON
    }
  }
}
