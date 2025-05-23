package io.quarkus.qute.runtime;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import jakarta.interceptor.Interceptor;

import org.jboss.logging.Logger;

import io.quarkus.arc.All;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ArcContainer;
import io.quarkus.arc.InjectableBean;
import io.quarkus.arc.InstanceHandle;
import io.quarkus.qute.Engine;
import io.quarkus.qute.EngineBuilder;
import io.quarkus.qute.EvalContext;
import io.quarkus.qute.Expression;
import io.quarkus.qute.FragmentNamespaceResolver;
import io.quarkus.qute.HtmlEscaper;
import io.quarkus.qute.ImmutableList;
import io.quarkus.qute.JsonEscaper;
import io.quarkus.qute.NamedArgument;
import io.quarkus.qute.NamespaceResolver;
import io.quarkus.qute.ParserHook;
import io.quarkus.qute.Qute;
import io.quarkus.qute.ReflectionValueResolver;
import io.quarkus.qute.Resolver;
import io.quarkus.qute.Results;
import io.quarkus.qute.SectionHelperFactory;
import io.quarkus.qute.StrEvalNamespaceResolver;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateGlobalProvider;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.qute.TemplateInstance.Initializer;
import io.quarkus.qute.TemplateLocator;
import io.quarkus.qute.TemplateLocator.TemplateLocation;
import io.quarkus.qute.UserTagSectionHelper;
import io.quarkus.qute.ValueResolver;
import io.quarkus.qute.ValueResolvers;
import io.quarkus.qute.Variant;
import io.quarkus.qute.runtime.QuteRecorder.QuteContext;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.LocalesBuildTimeConfig;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.Startup;

@Startup(Interceptor.Priority.PLATFORM_BEFORE)
@Singleton
public class EngineProducer {

    public static final String INJECT_NAMESPACE = "inject";
    public static final String CDI_NAMESPACE = "cdi";
    public static final String DEPENDENT_INSTANCES = "q_dep_inst";

    private static final String TAGS = "tags/";

    private static final Logger LOGGER = Logger.getLogger(EngineProducer.class);

    private final Engine engine;
    private final ContentTypes contentTypes;
    private final List<String> suffixes;
    private final Set<String> templateRoots;
    private final Map<String, String> templateContents;
    private final List<Pattern> templatePathExcludes;
    private final Locale defaultLocale;
    private final Charset defaultCharset;
    private final ArcContainer container;

    public EngineProducer(QuteContext context, QuteConfig config, QuteRuntimeConfig runtimeConfig,
            Event<EngineBuilder> builderReady, Event<Engine> engineReady, ContentTypes contentTypes,
            LaunchMode launchMode, LocalesBuildTimeConfig locales, @All List<TemplateLocator> locators,
            @All List<SectionHelperFactory<?>> sectionHelperFactories, @All List<ValueResolver> valueResolvers,
            @All List<NamespaceResolver> namespaceResolvers, @All List<ParserHook> parserHooks) {
        this.contentTypes = contentTypes;
        this.suffixes = config.suffixes();
        this.templateRoots = context.getTemplateRoots();
        this.templateContents = Map.copyOf(context.getTemplateContents());
        this.defaultLocale = locales.defaultLocale().orElse(Locale.getDefault());
        this.defaultCharset = config.defaultCharset();
        this.container = Arc.container();

        ImmutableList.Builder<Pattern> excludesBuilder = ImmutableList.<Pattern> builder()
                .add(config.templatePathExclude());
        for (String p : context.getExcludePatterns()) {
            excludesBuilder.add(Pattern.compile(p));
        }
        this.templatePathExcludes = excludesBuilder.build();

        LOGGER.debugf("Initializing Qute [templates: %s, tags: %s, resolvers: %s", context.getTemplatePaths(),
                context.getTags(),
                context.getResolverClasses());

        EngineBuilder builder = Engine.builder();

        // We don't register the map resolver because of param declaration validation
        builder.addValueResolver(ValueResolvers.thisResolver());
        builder.addValueResolver(ValueResolvers.orResolver());
        builder.addValueResolver(ValueResolvers.trueResolver());
        builder.addValueResolver(ValueResolvers.collectionResolver());
        builder.addValueResolver(ValueResolvers.mapperResolver());
        builder.addValueResolver(ValueResolvers.mapEntryResolver());
        // foo.string.raw returns a RawString which is never escaped
        builder.addValueResolver(ValueResolvers.rawResolver());
        builder.addValueResolver(ValueResolvers.logicalAndResolver());
        builder.addValueResolver(ValueResolvers.logicalOrResolver());
        builder.addValueResolver(ValueResolvers.orEmpty());
        // Note that arrays are handled specifically during validation
        builder.addValueResolver(ValueResolvers.arrayResolver());
        // Named arguments for fragment namespace resolver
        builder.addValueResolver(new NamedArgument.SetValueResolver());
        // Additional value resolvers
        for (ValueResolver valueResolver : valueResolvers) {
            builder.addValueResolver(valueResolver);
        }

        // Enable/disable strict rendering
        if (runtimeConfig.strictRendering()) {
            builder.strictRendering(true);
        } else {
            builder.strictRendering(false);
            // If needed, use a specific result mapper for the selected strategy
            if (runtimeConfig.propertyNotFoundStrategy().isPresent()) {
                switch (runtimeConfig.propertyNotFoundStrategy().get()) {
                    case THROW_EXCEPTION:
                        builder.addResultMapper(new PropertyNotFoundThrowException());
                        break;
                    case NOOP:
                        builder.addResultMapper(new PropertyNotFoundNoop());
                        break;
                    case OUTPUT_ORIGINAL:
                        builder.addResultMapper(new PropertyNotFoundOutputOriginal());
                        break;
                    default:
                        // Use the default strategy
                        break;
                }
            } else {
                // Throw an exception in the development mode
                if (launchMode == LaunchMode.DEVELOPMENT) {
                    builder.addResultMapper(new PropertyNotFoundThrowException());
                }
            }
        }

        // Escape some characters for HTML/XML templates
        builder.addResultMapper(new HtmlEscaper(List.copyOf(config.escapeContentTypes())));

        // Escape some characters for JSON templates
        builder.addResultMapper(new JsonEscaper());

        // Fallback reflection resolver
        builder.addValueResolver(new ReflectionValueResolver());

        // Remove standalone lines if desired
        builder.removeStandaloneLines(runtimeConfig.removeStandaloneLines());

        // Iteration metadata prefix
        builder.iterationMetadataPrefix(config.iterationMetadataPrefix());

        // Default section helpers
        builder.addDefaultSectionHelpers();
        // Additional section helpers
        for (SectionHelperFactory<?> sectionHelperFactory : sectionHelperFactories) {
            builder.addSectionHelper(sectionHelperFactory);
        }

        // Allow anyone to customize the builder
        builderReady.fire(builder);

        // Resolve @Named beans
        builder.addNamespaceResolver(NamespaceResolver.builder(INJECT_NAMESPACE).resolve(this::resolveInject).build());
        builder.addNamespaceResolver(NamespaceResolver.builder(CDI_NAMESPACE).resolve(this::resolveInject).build());
        // Additional namespace resolvers
        for (NamespaceResolver namespaceResolver : namespaceResolvers) {
            builder.addNamespaceResolver(namespaceResolver);
        }
        // str:eval
        builder.addNamespaceResolver(new StrEvalNamespaceResolver());
        // Fragment namespace resolvers
        builder.addNamespaceResolver(new NamedArgument.ParamNamespaceResolver());
        builder.addNamespaceResolver(new FragmentNamespaceResolver(FragmentNamespaceResolver.FRAGMENT));
        builder.addNamespaceResolver(new FragmentNamespaceResolver(FragmentNamespaceResolver.FRG));
        builder.addNamespaceResolver(new FragmentNamespaceResolver(FragmentNamespaceResolver.CAPTURE));
        builder.addNamespaceResolver(new FragmentNamespaceResolver(FragmentNamespaceResolver.CAP));

        // Add generated resolvers
        for (String resolverClass : context.getResolverClasses()) {
            Resolver resolver = createResolver(resolverClass);
            if (resolver instanceof NamespaceResolver) {
                builder.addNamespaceResolver((NamespaceResolver) resolver);
            } else {
                builder.addValueResolver((ValueResolver) resolver);
            }
            LOGGER.debugf("Added generated value resolver: %s", resolverClass);
        }
        // Add tags
        for (String tag : context.getTags()) {
            // Strip suffix, item.html -> item
            String tagName = tag.contains(".") ? tag.substring(0, tag.indexOf('.')) : tag;
            String tagTemplateId = TAGS + tagName;
            LOGGER.debugf("Registered UserTagSectionHelper for %s [%s]", tagName, tagTemplateId);
            builder.addSectionHelper(new UserTagSectionHelper.Factory(tagName, tagTemplateId));
        }
        // Add locator
        builder.addLocator(this::locate);
        registerCustomLocators(builder, locators);

        // Add parser hooks
        for (ParserHook parserHook : parserHooks) {
            builder.addParserHook(parserHook);
        }
        // Add a special parser hook for Qute.fmt() methods
        builder.addParserHook(new Qute.IndexedArgumentsParserHook());

        // Add global providers
        for (String globalProviderClass : context.getTemplateGlobalProviderClasses()) {
            TemplateGlobalProvider provider = createGlobalProvider(globalProviderClass);
            builder.addTemplateInstanceInitializer(provider);
            builder.addNamespaceResolver(provider);
        }

        // Add a special initializer for templates that contain an inject/cdi namespace expressions
        Map<String, Boolean> discoveredInjectTemplates = new HashMap<>();
        builder.addTemplateInstanceInitializer(new Initializer() {

            @Override
            public void accept(TemplateInstance instance) {
                Boolean hasInject = discoveredInjectTemplates.get(instance.getTemplate().getGeneratedId());
                if (hasInject == null) {
                    hasInject = hasInjectExpression(instance.getTemplate());
                }
                if (hasInject) {
                    // Add dependent beans map if the template contains a cdi namespace expression
                    instance.setAttribute(DEPENDENT_INSTANCES, new ConcurrentHashMap<>());
                    // Add a close action to destroy all dependent beans
                    instance.onRendered(new Runnable() {
                        @Override
                        public void run() {
                            Object dependentInstances = instance.getAttribute(EngineProducer.DEPENDENT_INSTANCES);
                            if (dependentInstances != null) {
                                @SuppressWarnings("unchecked")
                                ConcurrentMap<String, InstanceHandle<?>> existing = (ConcurrentMap<String, InstanceHandle<?>>) dependentInstances;
                                if (!existing.isEmpty()) {
                                    for (InstanceHandle<?> handle : existing.values()) {
                                        handle.close();
                                    }
                                }
                            }
                        }
                    });
                }
            }
        });

        builder.timeout(runtimeConfig.timeout());
        builder.useAsyncTimeout(runtimeConfig.useAsyncTimeout());

        engine = builder.build();

        // Load discovered template files
        Map<String, List<Template>> discovered = new HashMap<>();
        for (String path : context.getTemplatePaths()) {
            Template template = engine.getTemplate(path);
            if (template != null) {
                for (String suffix : config.suffixes()) {
                    if (path.endsWith(suffix)) {
                        String pathNoSuffix = path.substring(0, path.length() - (suffix.length() + 1));
                        List<Template> templates = discovered.get(pathNoSuffix);
                        if (templates == null) {
                            templates = new ArrayList<>();
                            discovered.put(pathNoSuffix, templates);
                        }
                        templates.add(template);
                        break;
                    }
                }
                discoveredInjectTemplates.put(template.getGeneratedId(), hasInjectExpression(template));
            }
        }
        // If it's a default suffix then register a path without suffix as well
        // hello.html -> hello, hello.html
        for (Entry<String, List<Template>> e : discovered.entrySet()) {
            processDefaultTemplate(e.getKey(), e.getValue(), config, engine);
        }

        engineReady.fire(engine);

        // Set the engine instance
        Qute.setEngine(engine);
    }

    private void registerCustomLocators(EngineBuilder builder,
            List<TemplateLocator> locators) {
        if (locators != null && !locators.isEmpty()) {
            for (TemplateLocator locator : locators) {
                builder.addLocator(locator);
            }
        }
    }

    @Produces
    @ApplicationScoped
    Engine getEngine() {
        return engine;
    }

    void onShutdown(@Observes ShutdownEvent event) {
        // Make sure to clear the Qute cache
        Qute.clearCache();
    }

    private Resolver createResolver(String resolverClassName) {
        try {
            Class<?> resolverClazz = Thread.currentThread()
                    .getContextClassLoader().loadClass(resolverClassName);
            if (Resolver.class.isAssignableFrom(resolverClazz)) {
                return (Resolver) resolverClazz.getDeclaredConstructor().newInstance();
            }
            throw new IllegalStateException("Not a resolver: " + resolverClassName);
        } catch (InstantiationException | IllegalAccessException | ClassNotFoundException | IllegalArgumentException
                | InvocationTargetException | NoSuchMethodException | SecurityException e) {
            throw new IllegalStateException("Unable to create resolver: " + resolverClassName, e);
        }
    }

    private TemplateGlobalProvider createGlobalProvider(String initializerClassName) {
        try {
            Class<?> initializerClazz = Thread.currentThread()
                    .getContextClassLoader().loadClass(initializerClassName);
            if (TemplateGlobalProvider.class.isAssignableFrom(initializerClazz)) {
                return (TemplateGlobalProvider) initializerClazz.getDeclaredConstructor().newInstance();
            }
            throw new IllegalStateException("Not a global provider: " + initializerClazz);
        } catch (InstantiationException | IllegalAccessException | ClassNotFoundException | IllegalArgumentException
                | InvocationTargetException | NoSuchMethodException | SecurityException e) {
            throw new IllegalStateException("Unable to create global provider: " + initializerClassName, e);
        }
    }

    private boolean isExcluded(String path) {
        for (Pattern p : templatePathExcludes) {
            if (p.matcher(path).matches()) {
                return true;
            }
        }
        return false;
    }

    private Optional<TemplateLocation> locate(String path) {
        if (isExcluded(path)) {
            return Optional.empty();
        }
        // First try the template contents, i.e. templates not backed by files
        LOGGER.debugf("Locate template contents for %s", path);
        String content = templateContents.get(path);
        if (content == null) {
            // Try path with suffixes
            for (String suffix : suffixes) {
                String pathWithSuffix = path + "." + suffix;
                if (isExcluded(pathWithSuffix)) {
                    continue;
                }
                content = templateContents.get(pathWithSuffix);
                if (content != null) {
                    break;
                }
            }
        }
        if (content != null) {
            return Optional.of(new ContentTemplateLocation(content, createVariant(path)));
        }

        // Then try to locate file-based templates
        for (String templateRoot : templateRoots) {
            URL resource = null;
            String templatePath = templateRoot + path;
            LOGGER.debugf("Locate template file for %s", templatePath);
            resource = locatePath(templatePath);
            if (resource == null) {
                // Try path with suffixes
                for (String suffix : suffixes) {
                    String pathWithSuffix = path + "." + suffix;
                    if (isExcluded(pathWithSuffix)) {
                        continue;
                    }
                    templatePath = templateRoot + pathWithSuffix;
                    resource = locatePath(templatePath);
                    if (resource != null) {
                        break;
                    }
                }
            }
            if (resource != null) {
                return Optional.of(new ResourceTemplateLocation(resource, createVariant(templatePath)));
            }
        }

        return Optional.empty();
    }

    private URL locatePath(String path) {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            cl = EngineProducer.class.getClassLoader();
        }
        return cl.getResource(path);
    }

    Variant createVariant(String path) {
        // Guess the content type from the path
        String contentType = contentTypes.getContentType(path);
        return new Variant(defaultLocale, defaultCharset, contentType);
    }

    private Object resolveInject(EvalContext ctx) {
        InjectableBean<?> bean = container.namedBean(ctx.getName());
        if (bean != null) {
            if (bean.getScope().equals(Dependent.class)) {
                // Dependent beans are shared across all expressions in a template for a single rendering operation
                Object dependentInstances = ctx.getAttribute(EngineProducer.DEPENDENT_INSTANCES);
                if (dependentInstances != null) {
                    @SuppressWarnings("unchecked")
                    ConcurrentMap<String, InstanceHandle<?>> existing = (ConcurrentMap<String, InstanceHandle<?>>) dependentInstances;
                    return existing.computeIfAbsent(ctx.getName(), name -> container.instance(bean)).get();
                }
            }
            return container.instance(bean).get();
        }
        return Results.NotFound.from(ctx);
    }

    private boolean hasInjectExpression(Template template) {
        for (Expression expression : template.getExpressions()) {
            if (isInjectExpression(expression)) {
                return true;
            }
        }
        return false;
    }

    private boolean isInjectExpression(Expression expression) {
        String namespace = expression.getNamespace();
        if (namespace != null && (CDI_NAMESPACE.equals(namespace) || INJECT_NAMESPACE.equals(namespace))) {
            return true;
        }
        for (Expression.Part part : expression.getParts()) {
            if (part.isVirtualMethod()) {
                for (Expression param : part.asVirtualMethod().getParameters()) {
                    if (param.isLiteral()) {
                        continue;
                    }
                    if (isInjectExpression(param)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void processDefaultTemplate(String path, List<Template> templates, QuteConfig config, Engine engine) {
        if (engine.isTemplateLoaded(path)) {
            return;
        }
        for (String suffix : config.suffixes()) {
            for (Template template : templates) {
                if (template.getId().endsWith(suffix)) {
                    engine.putTemplate(path, template);
                    return;
                }
            }
        }
    }

    static class ResourceTemplateLocation implements TemplateLocation {

        private final URL resource;
        private final Optional<Variant> variant;

        ResourceTemplateLocation(URL resource, Variant variant) {
            this.resource = resource;
            this.variant = Optional.ofNullable(variant);
        }

        @Override
        public Reader read() {
            Charset charset = null;
            if (variant.isPresent()) {
                charset = variant.get().getCharset();
            }
            if (charset == null) {
                charset = StandardCharsets.UTF_8;
            }
            try {
                return new InputStreamReader(resource.openStream(), charset);
            } catch (IOException e) {
                return null;
            }
        }

        @Override
        public Optional<Variant> getVariant() {
            return variant;
        }

    }

    static class ContentTemplateLocation implements TemplateLocation {

        private final String content;
        private final Optional<Variant> variant;

        ContentTemplateLocation(String content, Variant variant) {
            this.content = content;
            this.variant = Optional.ofNullable(variant);
        }

        @Override
        public Reader read() {
            return new StringReader(content);
        }

        @Override
        public Optional<Variant> getVariant() {
            return variant;
        }

    }

}
