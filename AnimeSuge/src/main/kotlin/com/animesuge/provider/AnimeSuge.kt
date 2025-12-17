> Configure project :akwam
Fetching JAR

> Task :akwam:checkKotlinGradlePluginConfigurationErrors SKIPPED
> Task :akwam:preBuild UP-TO-DATE
> Task :akwam:preDebugBuild UP-TO-DATE
> Task :akwam:generateDebugResValues
> Task :akwam:generateDebugResources
> Task :akwam:packageDebugResources
> Task :AnimeSuge:checkKotlinGradlePluginConfigurationErrors SKIPPED
> Task :AnimeSuge:preBuild UP-TO-DATE
> Task :AnimeSuge:preDebugBuild UP-TO-DATE
> Task :akwam:javaPreCompileDebug
> Task :AnimeSuge:generateDebugResValues
> Task :AnimeSuge:generateDebugResources
> Task :AnimeSuge:packageDebugResources
> Task :animezid:checkKotlinGradlePluginConfigurationErrors SKIPPED
> Task :AnimeSuge:javaPreCompileDebug
> Task :animezid:preBuild UP-TO-DATE
> Task :animezid:preDebugBuild UP-TO-DATE
> Task :akwam:parseDebugLocalResources
> Task :AnimeSuge:parseDebugLocalResources
> Task :animezid:generateDebugResValues
> Task :animezid:generateDebugResources
> Task :animezid:packageDebugResources
> Task :akwam:generateDebugRFile
> Task :AnimeSuge:generateDebugRFile
> Task :animezid:javaPreCompileDebug
> Task :animezid:parseDebugLocalResources
> Task :Arabseed:checkKotlinGradlePluginConfigurationErrors SKIPPED
> Task :Arabseed:preBuild UP-TO-DATE
> Task :Arabseed:preDebugBuild UP-TO-DATE
> Task :Arabseed:generateDebugResValues
> Task :Arabseed:generateDebugResources
> Task :animezid:generateDebugRFile
> Task :Arabseed:packageDebugResources
> Task :Arabseed:parseDebugLocalResources
> Task :Arabseed:generateDebugRFile

e: file:///home/runner/work/TestPlugins1/TestPlugins1/src/AnimeSuge/src/main/kotlin/com/animesuge/provider/AnimeSuge.kt:92:17 'constructor(data: String, name: String? = ..., season: Int? = ..., episode: Int? = ..., posterUrl: String? = ..., score: Score? = ..., description: String? = ..., date: Long? = ..., runTime: Int? = ...): Episode' is deprecated. Use newEpisode method.
e: file:///home/runner/work/TestPlugins1/TestPlugins1/src/AnimeSuge/src/main/kotlin/com/animesuge/provider/AnimeSuge.kt:114:22 Unresolved reference 'totalEpisodes'.
> Task :AnimeSuge:compileDebugKotlin

> Task :AnimeSuge:compileDebugKotlin FAILED
> Task :animezid:compileDebugKotlin
> Task :akwam:compileDebugKotlin
> Task :Arabseed:compileDebugKotlin

FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':AnimeSuge:compileDebugKotlin'.
> A failure occurred while executing org.jetbrains.kotlin.compilerRunner.GradleCompilerRunnerWithWorkers$GradleKotlinCompilerWorkAction
w: ATTENTION!
This build uses unsafe internal compiler arguments:

-XXLanguage:+BreakContinueInInlineLambdas

This mode is not recommended for production use,
as no stability/compatibility guarantees are given on
compiler or generated code. Use it at your own risk!

   > Compilation error. See log for more details

* Try:
> Run with --stacktrace option to get the stack trace.
> Run with --info or --debug option to get more log output.
> Run with --scan to get full insights.
> Get more help at https://help.gradle.org.

BUILD FAILED in 2m 57s
27 actionable tasks: 27 executed
