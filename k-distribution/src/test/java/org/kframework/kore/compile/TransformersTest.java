// Copyright (c) 2015-2016 K Team. All Rights Reserved.

package org.kframework.kore.compile;

import org.junit.Test;
import org.junit.rules.TestName;
import org.kframework.krun.KRunOptions;
import org.kframework.krun.KRun;
import org.kframework.attributes.Source;
import org.kframework.backend.Backends;
import org.kframework.builtin.Sorts;
import org.kframework.definition.Module;
import org.kframework.kale.KaleBackend;
import org.kframework.kale.KaleRewriter;
import org.kframework.kompile.CompiledDefinition;
import org.kframework.kompile.Kompile;
import org.kframework.kompile.KompileOptions;
import org.kframework.kore.K;
import org.kframework.kore.KApply;
import org.kframework.kore.KORE;
import org.kframework.kore.KToken;
import org.kframework.main.GlobalOptions;
import org.kframework.parser.ProductionReference;
import org.kframework.unparser.AddBrackets;
import org.kframework.unparser.KOREToTreeNodes;
import org.kframework.utils.errorsystem.KExceptionManager;
import org.kframework.utils.file.FileUtil;
import com.beust.jcommander.JCommander;
import org.junit.Before;
import org.junit.Test;
import org.kframework.AbstractTest;
import org.kframework.Kapi;
import org.kframework.RewriterResult;
import org.kframework.attributes.Source;
import org.kframework.kore.K;
import org.kframework.kore.compile.KtoKORE;
import org.kframework.main.GlobalOptions;
import org.kframework.utils.KoreUtils;
import org.kframework.utils.file.FileUtil;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URISyntaxException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;

import static org.junit.Assert.*;
import static org.kframework.kore.KORE.*;

public class TransformersTest extends AbstractTest {

    @org.junit.Rule
    public TestName name = new TestName();

    protected File testResource(String baseName) throws URISyntaxException {
        return new File(baseName);
    }

    private KoreUtils utils;
    private K parsed;
    private KtoKORE trans;
    private String fileName;
    private KRunOptions kRunOptions;
    private String pgm;

    @Test
    public void simple() throws IOException, URISyntaxException {
        //from k-distribution/src/test/java/org/kframework/krun/TstKRunOnKORE_IT.java
        // redirect system stdout to outstream and save in baos
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream outstream = new PrintStream(baos);
        PrintStream old = System.out; // save the previous System.out
        System.setOut(outstream);

        //from k-distribution/src/test/java/org/kframework/kore/compile/IMPonKale.java
        String fileName = "tutorial/2_languages/1_simple/2_typed/1_static/simple-typed-static.k";
        String mainModuleName = "SIMPLE";
        KExceptionManager kem = new KExceptionManager(new GlobalOptions());
        File definitionFile = testResource(fileName);
        KompileOptions kompileOptions = new KompileOptions();
        kompileOptions.backend = Backends.JAVA;
        GlobalOptions globalOptions = new GlobalOptions();
        globalOptions.debug = true;
        globalOptions.warnings = GlobalOptions.Warnings.ALL;
        Kompile kompile = new Kompile(kompileOptions, FileUtil.testFileUtil(), kem, false);
//        CompiledDefinition compiledDef = kompile.run(definitionFile, mainModuleName, mainModuleName, new KaleBackend(kompileOptions, kem).steps());

        // test
        String outstr = baos.toString();
        System.out.println("------------------------" + outstr);
        assertEquals("org.kframework.utils.errorsystem.KEMException: [Error] Critical: Incomplete rule assignment\n" +
                        "  while executing phase \"concretizing configuration\" on sentence at\n" +
                        "\tSource(.\\k-distribution\\tutorial\\2_languages\\1_simple\\2_typed\\1_static\\simple-typed-static.k)\n" +
                        "\tLocation(412,8,412,77)\n", outstr);

        // redirect output to stdout again
        System.setOut(old);
        System.out.println(outstr);
    }
}