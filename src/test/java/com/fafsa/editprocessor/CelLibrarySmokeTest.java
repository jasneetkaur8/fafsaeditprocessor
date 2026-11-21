package com.fafsa.editprocessor;

import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.types.MapType;
import dev.cel.common.types.SimpleType;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerFactory;
import dev.cel.runtime.CelRuntime;
import dev.cel.runtime.CelRuntimeFactory;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Setup check only: proves the CEL library is on the classpath and can compile and evaluate
 * an expression against map data, the way our rules will. Safe to delete once Step 4 has real tests.
 */
class CelLibrarySmokeTest {

    @Test
    void compilesAndEvaluatesAnExpressionAgainstMapData() throws Exception {
        CelCompiler compiler = CelCompilerFactory.standardCelCompilerBuilder()
                .addVar("household", MapType.create(SimpleType.STRING, SimpleType.DYN))
                .build();
        CelAbstractSyntaxTree ast = compiler
                .compile("household.numberInCollege <= household.numberInHousehold")
                .getAst();
        CelRuntime runtime = CelRuntimeFactory.standardCelRuntimeBuilder().build();
        CelRuntime.Program program = runtime.createProgram(ast);

        Object ok = program.eval(Map.of("household", Map.of("numberInHousehold", 4L, "numberInCollege", 1L)));
        Object bad = program.eval(Map.of("household", Map.of("numberInHousehold", 2L, "numberInCollege", 5L)));

        assertThat(ok).isEqualTo(true);
        assertThat(bad).isEqualTo(false);
    }
}
