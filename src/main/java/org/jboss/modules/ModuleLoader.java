package org.jboss.modules;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;

import static org.jboss.modules.ConcurrentReferenceHashMap.ReferenceType.SOFT;
import static org.jboss.modules.ConcurrentReferenceHashMap.ReferenceType.STRONG;

/**
 * 
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 * @author <a href="mailto:jbailey@redhat.com">John Bailey</a>
 */
public abstract class ModuleLoader {

    private static volatile ModuleLogger log = new ModuleLogger() {
        public void moduleLoading(final ModuleIdentifier identifier) {
        }

        public void moduleLoaded(final ModuleIdentifier identifier) {
        }

        public void moduleLoadFailed(final ModuleIdentifier identifier, final Throwable cause) {
        }
    };

    /**
     * Set the logger to be used for module load events.
     *
     * @param moduleLogger the logger to use
     */
    public static void setLogger(ModuleLogger moduleLogger) {
        // todo perm check
        log = moduleLogger;
    }

    private ThreadLocal<Set<ModuleIdentifier>> VISITED = new ThreadLocal<Set<ModuleIdentifier>>() {
        @Override
        protected Set<ModuleIdentifier> initialValue() {
            return new LinkedHashSet<ModuleIdentifier>();
        }
    };

    private final ConcurrentMap<ModuleIdentifier, FutureModule> moduleMap = new ConcurrentReferenceHashMap<ModuleIdentifier, FutureModule>(
            256, 0.5f, 32, STRONG, SOFT, EnumSet.noneOf(ConcurrentReferenceHashMap.Option.class)
    );

    /**
     * Load a module based on an identifier.
     *
     * @param identifier The module identifier
     * @return The loaded Module
     * @throws ModuleLoadException if the Module can not be loaded
     */
    public Module loadModule(ModuleIdentifier identifier) throws ModuleLoadException {
        if (identifier.equals(ModuleIdentifier.SYSTEM)) {
            return Module.SYSTEM;
        }

        final Set<ModuleIdentifier> visited = VISITED.get();

        if(visited.contains(identifier))
            throw new ModuleLoadException("Failed to load " + identifier + "; module cycle discovered: " + visited);

        FutureModule futureModule = moduleMap.get(identifier);
        if (futureModule == null) {
            FutureModule newFuture = new FutureModule(identifier);
            futureModule = moduleMap.putIfAbsent(identifier, newFuture);
            if (futureModule == null) {
                visited.add(identifier);
                try {
                    log.moduleLoading(identifier);
                    final Module module = findModule(identifier);
                    if (module == null) {
                        final ModuleNotFoundException e = new ModuleNotFoundException(identifier.toString());
                        log.moduleLoadFailed(identifier, e);
                        throw e;
                    }
                    log.moduleLoaded(identifier);
                    return module;
                } finally {
                    visited.remove(identifier);
                }
            }
        }
        return futureModule.getModule();
    }

    /**
     * Find a Module by its identifier.  This should be overriden by sub-classes
     * to provide custom Module loading strategies.  Implementations of this method
     * should call {@link #defineModule}
     *
     * @param moduleIdentifier The modules Identifier
     * @return The Module
     * @throws ModuleLoadException If any problems occur finding the module
     */
    protected abstract Module findModule(final ModuleIdentifier moduleIdentifier) throws ModuleLoadException;

    /**
     * Defines a Module based on a specification.  Use of this method is required by
     * any ModuleLoader implementations in order to fully define a Module. 
     *
     * @param moduleSpec The module specification to create the Module from
     * @return The defined Module
     * @throws ModuleLoadException If any dependent modules can not be loaded
     */
    protected final Module defineModule(ModuleSpec moduleSpec) throws ModuleLoadException {

        final ModuleIdentifier moduleIdentifier = moduleSpec.getIdentifier();
        FutureModule futureModule = moduleMap.get(moduleIdentifier);
        if (futureModule == null) {
            FutureModule newFuture = new FutureModule(moduleIdentifier);
            futureModule = moduleMap.putIfAbsent(moduleIdentifier, newFuture);
            if (futureModule == null) futureModule = newFuture;
        }
        // early detect
        if (futureModule.module != null) {
            throw new ModuleAlreadyExistsException(moduleIdentifier.toString());
        }

        final Map<String, List<Dependency>> pathsToImports = new HashMap<String, List<Dependency>>();
        final Set<String> exportedPaths = new HashSet<String>();
        try {
            final List<Dependency> dependencies = new ArrayList<Dependency>(moduleSpec.getDependencies().size());
            for (DependencySpec dependencySpec : moduleSpec.getDependencies()) {
                final Module dependencyModule;
                try {
                    dependencyModule = loadModule(dependencySpec.getModuleIdentifier());
                } catch (ModuleLoadException e) {
                    if (dependencySpec.isOptional()) {
                        continue;
                    } else {
                        throw e;
                    }
                }
                final Dependency dependency = new Dependency(dependencyModule, dependencySpec.isExport());
                dependencies.add(dependency);

                final ExportFilter filter = dependencySpec.getExportFilter();

                final Set<String> moduleExportedPaths = dependencyModule.getExportedPaths();
                if(dependency.isExport()) {
                    for(String exportedPath : moduleExportedPaths) {
                        if(filter.shouldExport(exportedPath))
                            exportedPaths.add(exportedPath);
                    }
                }
                for(String path : moduleExportedPaths) {
                    if(!pathsToImports.containsKey(path))
                        pathsToImports.put(path, new ArrayList<Dependency>());
                    pathsToImports.get(path).add(dependency);
                }
            }

            final ModuleContentLoader contentLoader = moduleSpec.getContentLoader();
            exportedPaths.addAll(contentLoader.getFilteredLocalPaths());
            
            final Module module = new Module(moduleSpec, moduleSpec.getModuleFlags(), this, exportedPaths, pathsToImports);
            synchronized (futureModule) {
                futureModule.setModule(module);
            }
            return module;
        } catch (ModuleLoadException e) {
            futureModule.setModule(null);
            log.moduleLoadFailed(moduleIdentifier, e);
            throw e;
        } catch (RuntimeException e) {
            futureModule.setModule(null);
            log.moduleLoadFailed(moduleIdentifier, e);
            throw e;
        } catch (Error e) {
            futureModule.setModule(null);
            log.moduleLoadFailed(moduleIdentifier, e);
            throw e;
        }
    }

    /**
     * Create an aggregate module based on a module identifier and list of dependencies to import/export.
     *
     * @param moduleIdentifier The module identifier
     * @param dependencies The module identifiers to aggregate
     * @return The loaded Module
     * @throws ModuleLoadException If any dependent module can not be loaded
     */
    public Module createAggregate(ModuleIdentifier moduleIdentifier, List<ModuleIdentifier> dependencies) throws ModuleLoadException {

        final ModuleSpec moduleSpec = new ModuleSpec(moduleIdentifier);
        for(ModuleIdentifier identifier : dependencies) {
            DependencySpec dependencySpec = new DependencySpec();
            dependencySpec.setModuleIdentifier(identifier);
            dependencySpec.setExport(true);
            moduleSpec.addDependency(dependencySpec);
        }

        moduleSpec.setContentLoader(ModuleContentLoader.build().create());
        return defineModule(moduleSpec);
    }

    private static final class FutureModule {
        private static final Object NOT_FOUND = new Object();

        private final ModuleIdentifier identifier;
        private volatile Object module;

        FutureModule(final ModuleIdentifier identifier) {
            this.identifier = identifier;
        }

        Module getModule() throws ModuleNotFoundException {
            boolean intr = false;
            try {
                Object module = this.module;
                if (module == null) synchronized (this) {
                    while ((module = this.module) == null) {
                        try {
                            wait();
                        } catch (InterruptedException e) {
                            intr = true;
                        }
                    }
                }
                if (module == NOT_FOUND) throw new ModuleNotFoundException(identifier.toString());
                return (Module) module;
            } finally {
                if (intr) Thread.currentThread().interrupt();
            }
        }

        void setModule(Module m) throws ModuleAlreadyExistsException {
            synchronized (this) {
                module = m == null ? NOT_FOUND : m;
                notifyAll();
            }
        }
    }
}
