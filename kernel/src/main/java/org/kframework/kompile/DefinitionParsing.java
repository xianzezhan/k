// Copyright (c) 2015-2016 K Team. All Rights Reserved.
package org.kframework.kompile;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.apache.commons.collections15.ListUtils;
import org.kframework.Collections;
import org.kframework.attributes.Source;
import org.kframework.builtin.BooleanUtils;
import org.kframework.definition.Bubble;
import org.kframework.definition.Context;
import org.kframework.definition.Definition;
import org.kframework.definition.DefinitionTransformer;
import org.kframework.definition.HybridMemoizingModuleTransformer;
import org.kframework.definition.Module;
import org.kframework.definition.ModuleName;
import org.kframework.definition.Rule;
import org.kframework.definition.Sentence;
import org.kframework.kore.K;
import org.kframework.kore.KApply;
import org.kframework.kore.Sort;
import org.kframework.parser.TreeNodesToKORE;
import org.kframework.parser.concrete2kore.ParseCache;
import org.kframework.parser.concrete2kore.ParseCache.ParsedSentence;
import org.kframework.parser.concrete2kore.ParseInModule;
import org.kframework.parser.concrete2kore.ParserUtils;
import org.kframework.parser.concrete2kore.generator.RuleGrammarGenerator;
import org.kframework.utils.BinaryLoader;
import org.kframework.utils.StringUtil;
import org.kframework.utils.errorsystem.KEMException;
import org.kframework.utils.errorsystem.KExceptionManager;
import org.kframework.utils.errorsystem.ParseFailedException;
import org.kframework.utils.file.FileUtil;
import scala.Tuple2;
import scala.collection.Set;
import scala.util.Either;
import scala.util.Left;
import scala.util.Right;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.kframework.Collections.*;
import static org.kframework.definition.Constructors.Att;
import static org.kframework.definition.Constructors.*;
import static org.kframework.kore.KORE.*;

/**
 * A bundle of code doing various aspects of definition parsing.
 * TODO: In major need of refactoring.
 *
 * @cos refactored this code out of Kompile but none (or close to none) of it was originally written by him.
 */
public class DefinitionParsing {
    public static final Sort START_SYMBOL = Sort("RuleContent", ModuleName.apply("REQUIRES-ENSURES"));
    private final File cacheFile;
    private boolean autoImportDomains;

    private final KExceptionManager kem;
    private final ParserUtils parser;
    private final boolean cacheParses;
    private final BinaryLoader loader;

    public final AtomicInteger parsedBubbles = new AtomicInteger(0);
    public final AtomicInteger cachedBubbles = new AtomicInteger(0);
    private final boolean isStrict;
    private final List<File> lookupDirectories;

    public DefinitionParsing(
            List<File> lookupDirectories,
            boolean isStrict,
            KExceptionManager kem,
            ParserUtils parser,
            boolean cacheParses,
            File cacheFile,
            boolean autoImportDomains) {
        this.lookupDirectories = lookupDirectories;
        this.kem = kem;
        this.parser = parser;
        this.cacheParses = cacheParses;
        this.cacheFile = cacheFile;
        this.autoImportDomains = autoImportDomains;
        this.loader = new BinaryLoader(this.kem);
        this.isStrict = isStrict;
    }

    public Module parseModule(CompiledDefinition definition, File definitionFile, boolean autoImportDomains) {
        java.util.Set<Module> modules = parser.loadModules(
                mutable(definition.getParsedDefinition().modules()),
                "require " + StringUtil.enquoteCString(definitionFile.getPath()),
                Source.apply(definitionFile.getAbsolutePath()),
                Lists.newArrayList(Kompile.BUILTIN_DIRECTORY, definitionFile.getParentFile()),
                autoImportDomains);

        if (modules.size() != 1) {
            throw KEMException.compilerError("Expected to find a file with 1 module: found " + modules.size() + " instead.");
        }

        Module module = modules.iterator().next();

        errors = java.util.Collections.synchronizedSet(Sets.newHashSet());
        caches = new HashMap<>();

        if (cacheParses) {
            try {
                caches = loader.load(Map.class, cacheFile);
            } catch (FileNotFoundException e) {
            } catch (IOException | ClassNotFoundException e) {
                kem.registerInternalHiddenWarning("Invalidating serialized cache due to corruption.", e);
            }
        }

        ResolveConfig resolveConfig = new ResolveConfig(definition.getParsedDefinition(), isStrict, this::parseBubble, this::getParser);
        Module modWithConfig = resolveConfig.apply(module);

        Module parsedMod = resolveNonConfigBubbles(modWithConfig, s -> definition.getParsedDefinition().getModule(s).get(), isStrict);

        saveCachesAndReportParsingErrors();
        return parsedMod;
    }

    private void saveCachesAndReportParsingErrors() {
        saveCaches();
        throwExceptionIfThereAreErrors();
    }

    private void saveCaches() {
        if (cacheParses) {
            loader.saveOrDie(cacheFile, caches);
        }
    }

    public Definition parseDefinitionAndResolveBubbles(File definitionFile, String mainModuleName, String mainProgramsModule) {

        List<File> allLookupDirectories = ListUtils.union(
                lookupDirectories,
                Lists.newArrayList(Kompile.BUILTIN_DIRECTORY));
        allLookupDirectories.add(0, definitionFile.getParentFile());

        return parseDefinitionAndResolveBubbles(FileUtil.load(definitionFile), mainModuleName, mainProgramsModule, Source.apply(definitionFile.toString()), allLookupDirectories);
    }

    public Definition parseDefinitionAndResolveBubbles(String definitionString, String mainModuleName, String mainProgramsModule, Source source, List<File> allLookupDirectories) {
        Definition parsedDefinition = RuleGrammarGenerator.autoGenerateBaseKCasts(parseDefinition(definitionString, mainModuleName, mainProgramsModule, source, allLookupDirectories));
        Definition afterResolvingConfigBubbles = resolveConfigBubbles(parsedDefinition);
        Definition afterResolvingAllOtherBubbles = resolveNonConfigBubbles(afterResolvingConfigBubbles);
        saveCachesAndReportParsingErrors();
        return afterResolvingAllOtherBubbles;
    }

    private void throwExceptionIfThereAreErrors() {
        if (!errors.isEmpty()) {
            kem.addAllKException(errors.stream().map(e -> e.exception).collect(Collectors.toList()));
            throw KEMException.compilerError("Had " + errors.size() + " parsing errors.");
        }
    }

    public Definition parseDefinition(String definitionString, String mainModuleName, String mainProgramsModule, Source source, List<File> allLookupDirectories) {

        Definition definition = parser.loadDefinition(
                mainModuleName,
                mainProgramsModule, definitionString,
                source,
                allLookupDirectories,
                autoImportDomains);
        return definition;
    }

    protected Definition resolveConfigBubbles(Definition definition) {
        boolean hasConfigDecl = stream(definition.mainModule().sentences())
                .filter(s -> s instanceof Bubble)
                .map(b -> (Bubble) b)
                .filter(b -> b.sentenceType().equals("config"))
                .findFirst().isPresent();

        if (!hasConfigDecl) {
            definitionWithConfigBubble = DefinitionTransformer.fromHybrid(mod -> {
                if (mod == definition.mainModule()) {
                    java.util.Set<Module> imports = mutable(mod.imports());
                    imports.add(definition.getModule("DEFAULT-CONFIGURATION").get());
                    return Module(mod.name(), (Set<Module>) immutable(imports), mod.localSentences(), mod.att());
                }
                return mod;
            }, "adding default configuration").apply(definition);
        } else {
            definitionWithConfigBubble = definition;
        }

        errors = java.util.Collections.synchronizedSet(Sets.newHashSet());
        caches = new HashMap<>();

        if (cacheParses) {
            try {
                caches = loader.load(Map.class, cacheFile);
            } catch (FileNotFoundException e) {
            } catch (IOException | ClassNotFoundException e) {
                kem.registerInternalHiddenWarning("Invalidating serialized cache due to corruption.", e);
            }
        }

        ResolveConfig resolveConfig = new ResolveConfig(definitionWithConfigBubble, isStrict, this::parseBubble, this::getParser);
        Definition defWithConfig = DefinitionTransformer.fromHybrid(resolveConfig::apply, "parsing configurations").apply(definitionWithConfigBubble);

        return defWithConfig;
    }

    Map<String, ParseCache> caches;
    private java.util.Set<KEMException> errors;
    Definition definitionWithConfigBubble;

    public java.util.Set<KEMException> errors() {
        return errors;
    }

    public Definition resolveNonConfigBubbles(Definition defWithConfig) {
        HybridMemoizingModuleTransformer resolveNonConfigBubbles = new HybridMemoizingModuleTransformer() {
            @Override
            public Module processHybridModule(Module hybridModule) {
                return resolveNonConfigBubbles(hybridModule, s -> apply(defWithConfig.getModule(s).get()), isStrict);
            }
        };

        Definition parsedDef = new DefinitionTransformer(resolveNonConfigBubbles).apply(defWithConfig);
        return parsedDef;
    }

    private Module resolveNonConfigBubbles(Module module, Function<String, Module> getProcessedModule, boolean isStrict) {
        if (stream(module.localSentences())
                .filter(s -> s instanceof Bubble)
                .map(b -> (Bubble) b)
                .filter(b -> !b.sentenceType().equals("config")).count() == 0)
            return module;
        Module ruleParserModule = RuleGrammarGenerator.getRuleGrammar(module, getProcessedModule);

        ParseCache cache = loadCache(ruleParserModule);
        ParseInModule parser = RuleGrammarGenerator.getCombinedGrammar(cache.getModule(), isStrict);

        java.util.Set<Bubble> bubbles = stream(module.localSentences())
                .parallel()
                .filter(s -> s instanceof Bubble)
                .map(b -> (Bubble) b).collect(Collectors.toSet());

        Set<Sentence> ruleSet = bubbles.stream()
                .filter(b -> b.sentenceType().equals("rule"))
                .map(b -> performParse(cache.getCache(), parser, b))
                .flatMap(r -> {
                    if (r.isRight()) {
                        return Stream.of(this.upRule(r.right().get()));
                    } else {
                        errors.addAll(r.left().get());
                        return Stream.empty();
                    }
                }).collect(Collections.toSet());

        Set<Sentence> contextSet = bubbles.stream()
                .filter(b -> b.sentenceType().equals("context"))
                .map(b -> performParse(cache.getCache(), parser, b))
                .flatMap(r -> {
                    if (r.isRight()) {
                        return Stream.of(this.upContext(r.right().get()));
                    } else {
                        errors.addAll(r.left().get());
                        return Stream.empty();
                    }
                }).collect(Collections.toSet());

        return Module(module.name(), module.imports(),
                stream((Set<Sentence>) module.localSentences().$bar(ruleSet).$bar(contextSet)).filter(b -> !(b instanceof Bubble)).collect(Collections.toSet()), module.att());
    }


    public Rule parseRule(CompiledDefinition compiledDef, String contents, Source source) {
        Either<java.util.Set<ParseFailedException>, K> res = performParse(new HashMap<>(), RuleGrammarGenerator.getCombinedGrammar(RuleGrammarGenerator.getRuleGrammar(compiledDef.executionModule(), s -> compiledDef.kompiledDefinition.getModule(s).get()), isStrict),
                new Bubble("rule", contents, Att().add("contentStartLine", 1).add("contentStartColumn", 1).add("Source", source.source())));
        if (res.isLeft()) {
            //System.out.println("gonna throw in parseRule due to res.isLeft() is ParseFailedException " + " contents: " + contents);
            throw res.left().get().iterator().next();
        }
        return upRule(res.right().get());
    }

    private Rule upRule(K contents) {
        KApply ruleContents = (KApply) contents;
        List<org.kframework.kore.K> items = ruleContents.klist().items();
        switch (ruleContents.klabel().name()) {
        case "#ruleNoConditions":
            return Rule(items.get(0), BooleanUtils.TRUE, BooleanUtils.TRUE, ruleContents.att());
        case "#ruleRequires":
            return Rule(items.get(0), items.get(1), BooleanUtils.TRUE, ruleContents.att());
        case "#ruleEnsures":
            return Rule(items.get(0), BooleanUtils.TRUE, items.get(1), ruleContents.att());
        case "#ruleRequiresEnsures":
            return Rule(items.get(0), items.get(1), items.get(2), ruleContents.att());
        default:
            throw new AssertionError("Wrong KLabel for rule content");
        }
    }

    private Context upContext(K contents) {
        KApply ruleContents = (KApply) contents;
        List<K> items = ruleContents.klist().items();
        switch (ruleContents.klabel().name()) {
        case "#ruleNoConditions":
            return Context(items.get(0), BooleanUtils.TRUE, ruleContents.att());
        case "#ruleRequires":
            return Context(items.get(0), items.get(1), ruleContents.att());
        default:
            throw KEMException.criticalError("Illegal context with ensures clause detected.", contents);
        }
    }

    private ParseCache loadCache(Module parser) {
        ParseCache cachedParser = caches.get(parser.name());
        if (cachedParser == null || !equalsSyntax(cachedParser.getModule(), parser) || cachedParser.isStrict() != isStrict) {
            cachedParser = new ParseCache(parser, isStrict, java.util.Collections.synchronizedMap(new HashMap<>()));
            caches.put(parser.name(), cachedParser);
        }
        return cachedParser;
    }

    private boolean equalsSyntax(Module _this, Module that) {
        if (!_this.productions().equals(that.productions())) return false;
        if (!_this.priorities().equals(that.priorities())) return false;
        if (!_this.leftAssoc().equals(that.leftAssoc())) return false;
        if (!_this.rightAssoc().equals(that.rightAssoc())) return false;
        return _this.sortDeclarations().equals(that.sortDeclarations());
    }

    private Either<java.util.Set<ParseFailedException>, K> parseBubble(Module module, Function<String, Module> getModule, Bubble b) {
        ParseCache cache = loadCache(RuleGrammarGenerator.getConfigGrammar(module, getModule));
        ParseInModule parser = RuleGrammarGenerator.getCombinedGrammar(cache.getModule(), isStrict);
        return performParse(cache.getCache(), parser, b);
    }

    private ParseInModule getParser(Module module, Function<String, Module> getModule) {
        ParseCache cache = loadCache(RuleGrammarGenerator.getConfigGrammar(module, getModule));
        return RuleGrammarGenerator.getCombinedGrammar(cache.getModule(), isStrict);
    }

    private Either<java.util.Set<ParseFailedException>, K> performParse(Map<String, ParsedSentence> cache, ParseInModule parser, Bubble b) {
        int startLine = b.att().<Integer>get("contentStartLine").get();
        int startColumn = b.att().<Integer>get("contentStartColumn").get();
        String source = b.att().<String>get("Source").get();
        Tuple2<Either<java.util.Set<ParseFailedException>, K>, java.util.Set<ParseFailedException>> result;
       // System.out.println("b contents: " + b.contents().toString());
        if (cache.containsKey(b.contents())) {
            ParsedSentence parse = cache.get(b.contents());
            cachedBubbles.getAndIncrement();
            kem.addAllKException(parse.getWarnings().stream().map(e -> e.getKException()).collect(Collectors.toList()));
            return Right.apply(parse.getParse());
        } else {
            //Fix the .TaskCellBag bug for issue #2097
            if(b.contents().equals("<tasks> .TaskCellBag </tasks>"))
                result = parser.parseString("<tasks> .Bag </tasks>", START_SYMBOL, Source.apply(source), startLine, startColumn);
            else
                result = parser.parseString(b.contents(), START_SYMBOL, Source.apply(source), startLine, startColumn);
            //System.out.println("result is: " + result.toString());
            parsedBubbles.getAndIncrement();
            kem.addAllKException(result._2().stream().map(e -> e.getKException()).collect(Collectors.toList()));
            //System.out.println("result is: " + result.toString());
            if (result._1().isRight()) {
                KApply k = (KApply) TreeNodesToKORE.down(result._1().right().get());
                k = KApply(k.klabel(), k.klist(), k.att().addAll(b.att().remove("contentStartLine").remove("contentStartColumn").remove("Source").remove("Location")));
                cache.put(b.contents(), new ParsedSentence(k, new HashSet<>(result._2())));
                return Right.apply(k);
            } else {
                return Left.apply(result._1().left().get());
            }
        }
    }
}
