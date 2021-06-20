package nu.studer.gradle.jooq

import groovy.sql.Sql
import org.gradle.testkit.runner.TaskOutcome
import org.gradle.util.GradleVersion
import org.jooq.Constants
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Unroll

import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.sql.DriverManager

@Unroll
class JooqFuncTest extends BaseFuncTest {

    private static final ALL_INPUTS_DECLARED_JOOQ_TASK = """
tasks.named('generateJooq').configure { allInputsDeclared = true }
"""

    private static final CACHE_JOOQ_TASK = """
tasks.named('generateJooq').configure { outputs.cacheIf { true } }
"""

    @AutoCleanup
    @Shared
    Sql sql

    void setupSpec() {
        sql = new Sql(DriverManager.getConnection('jdbc:h2:~/test;AUTO_SERVER=TRUE', 'sa', ''))
        sql.execute('CREATE SCHEMA IF NOT EXISTS jooq_test;')
        sql.execute('CREATE TABLE IF NOT EXISTS jooq_test.foo (a INT);')
    }

    @SuppressWarnings(['SqlNoDataSourceInspection', 'SqlResolve'])
    void cleanupSpec() {
        sql.execute('DROP TABLE jooq_test.foo')
        sql.execute('DROP SCHEMA jooq_test')
    }

    void "can invoke jOOQ task from configuration DSL with configuration name omitted from task name for 'main' configuration"() {
        given:
        buildFile << buildWithJooqPluginDSL()

        when:
        def result = runWithArguments('generateJooq')

        then:
        fileExists('build/generated-src/jooq/main/nu/studer/sample/jooq_test/tables/Foo.java')

        and:
        result.task(':generateJooq').outcome == TaskOutcome.SUCCESS
    }

    void "can invoke jOOQ task from configuration DSL with Gradle configuration cache enabled"() {
        given:
        gradleVersion = GradleVersion.version('6.9')
        buildFile << buildWithJooqPluginDSL()

        when:
        def result = runWithArguments('generateJooq', '--configuration-cache')

        then:
        fileExists('build/generated-src/jooq/main/nu/studer/sample/jooq_test/tables/Foo.java')
        result.output.contains("Calculating task graph as no configuration cache is available for tasks: generateJooq")
        result.task(':generateJooq').outcome == TaskOutcome.SUCCESS

        when:
        new File(workspaceDir, 'build/generated-src/jooq/main/nu/studer/sample/jooq_test/tables/Foo.java').delete()
        result = runWithArguments('generateJooq', '--configuration-cache')

        then:
        fileExists('build/generated-src/jooq/main/nu/studer/sample/jooq_test/tables/Foo.java')
        result.output.contains("Reusing configuration cache.")
        result.task(':generateJooq').outcome == TaskOutcome.SUCCESS
    }

    void "can invoke jOOQ task derived from configuration DSL with multiple items"() {
        given:
        buildFile << buildWithMultipleItemsJooqPluginDSL()

        when:
        def result = runWithArguments('generateSampleJooq')

        then:
        fileExists('build/generated-src/jooq/sample/nu/studer/sample_pkg/jooq_test/tables/Foo.java')
        !fileExists('build/generated-src/jooq/main/nu/studer/main_pkg/jooq_test/tables/Foo.java')

        and:
        result.task(':generateSampleJooq').outcome == TaskOutcome.SUCCESS
    }

    void "can invoke jOOQ task derived from configuration DSL using Kotlin DSL"() {
        given:
        file('build.gradle.kts') << """
import org.jooq.meta.jaxb.Logging
import org.jooq.meta.jaxb.Jdbc
import org.jooq.meta.jaxb.Generator
import org.jooq.meta.jaxb.Database
import org.jooq.meta.jaxb.Target

plugins {
    id("nu.studer.jooq")
    java
}

repositories {
    jcenter()
}

dependencies {
    jooqGenerator("com.h2database:h2:1.4.200")
}

jooq {
    version.set("3.14.7")
    configurations {
        create("main") {
            jooqConfiguration.apply {
                logging = Logging.WARN
                jdbc.apply {
                    driver = "org.h2.Driver"
                    url = "jdbc:h2:~/test;AUTO_SERVER=TRUE"
                    user = "sa"
                    password = ""
                }
                generator.apply {
                    database.apply {
                        name = "org.jooq.meta.h2.H2Database"
                        includes = ".*"
                        excludes = ""
                    }
                    target.packageName = "nu.studer.sample"
                }
            }
        }
    }
}
"""

        when:
        def result = runWithArguments('generateJooq')

        then:
        fileExists('build/generated-src/jooq/main/nu/studer/sample/jooq_test/tables/Foo.java')

        and:
        result.task(':generateJooq').outcome == TaskOutcome.SUCCESS
    }

    void "can compile Java source files generated by jOOQ as part of invoking Java compile task with the matching source set"() {
        given:
        buildFile << buildWithJooqPluginDSL(null, null, Boolean.TRUE)

        when:
        def result = runWithArguments('classes')

        then:
        fileExists('build/generated-src/jooq/main/nu/studer/sample/jooq_test/tables/Foo.java')
        fileExists('build/classes/java/main/nu/studer/sample/jooq_test/tables/Foo.class')
        result.task(':generateJooq').outcome == TaskOutcome.SUCCESS
        result.task(':classes').outcome == TaskOutcome.SUCCESS
    }

    void "can disable auto-generation of schema source by jOOQ as part of invoking Java compile task with the matching source set"() {
        given:
        buildFile << buildWithJooqPluginDSL(null, null, Boolean.FALSE)

        when:
        def result = runWithArguments('classes')

        then:
        !fileExists('build/generated-src/jooq/main/nu/studer/sample/jooq_test/tables/Foo.java')
        !fileExists('build/classes/java/main/nu/studer/sample/jooq_test/tables/Foo.class')
        !result.task(':generateJooq')
        result.task(':classes').outcome == TaskOutcome.UP_TO_DATE
    }

    void "can reconfigure the output dir"() {
        given:
        buildFile << buildWithJooqPluginDSL(null, 'src/generated/jooq')
        buildFile << """
def newTargetDir = file('src/generated/jooq/other')
jooq {
  configurations {
    main {
      generationTool {
        generator {
          target {
            directory = newTargetDir
          }
        }
      }
    }
  }
}

jooq.configurations.main.jooqConfiguration.generator.target.directory = file('src/generated/jooq/yet/another')

afterEvaluate {
  SourceSetContainer sourceSets = project.getConvention().getPlugin(JavaPluginConvention.class).getSourceSets()
  SourceSet sourceSet = sourceSets.findByName('main')
  Set<File> dirs = sourceSet.getJava().getSrcDirs()
  dirs.eachWithIndex { dir, index ->
    println "\$dir---"
  }
}
"""
        when:
        def result = runWithArguments('classes')

        then:
        !fileExists('src/generated/jooq/nu/studer/sample/jooq_test/tables/Foo.java')
        !fileExists('src/generated/jooq/other/nu/studer/sample/jooq_test/tables/Foo.java')
        fileExists('src/generated/jooq/yet/another/nu/studer/sample/jooq_test/tables/Foo.java')
        fileExists('build/classes/java/main/nu/studer/sample/jooq_test/tables/Foo.class')
        !result.output.contains('/src/generated/jooq---')
        !result.output.contains('/src/generated/jooq/other---')
        result.output.contains('/src/generated/jooq/yet/another---')
        result.output.contains('/src/main/java---')

        and:
        result.task(':generateJooq').outcome == TaskOutcome.SUCCESS
        result.task(':classes').outcome == TaskOutcome.SUCCESS
    }

    void "can set custom jOOQ version"() {
        given:
        buildFile << buildWithJooqPluginDSL(null, null, null, '3.13.1')

        when:
        def result = runWithArguments('dependencies')

        then:
        result.output.contains('org.jooq:jooq-codegen -> 3.13.1')
        result.output.contains('org.jooq:jooq -> 3.13.1')
    }

    void "can set custom jOOQ edition"() {
        given:
        buildFile << buildWithJooqPluginDSL(null, null, null, '3.13.4', JooqEdition.TRIAL)

        when:
        def result = runWithArguments('dependencies')

        then:
        result.output.contains('org.jooq:jooq-codegen -> org.jooq.trial:jooq-codegen:3.13.4')
        result.output.contains('org.jooq:jooq -> org.jooq.trial:jooq:3.13.4')
    }

    void "supports task avoidance"() {
        given:
        buildFile << buildWithJooqPluginDSL()
        buildFile << """
tasks.configureEach {
    println("\${Thread.currentThread().id} Configuring \${it.path}")
}

task dummy {}
"""
        when:
        def result = runWithArguments(task)

        then:
        result.output.contains('Configuring :generateJooq') == expectGenerateJooq
        result.output.contains('Configuring :compileJava') == expectCompileJava

        where:
        task          | expectGenerateJooq | expectCompileJava
        'dummy'       | false              | false
        // 'generateJooq' | true                | false
        'compileJava' | true               | true

    }

    void "does not participate in incremental build by default"() {
        given:
        buildFile << buildWithJooqPluginDSL()

        when:
        def result = runWithArguments('generateJooq')

        then:
        result.task(':generateJooq').outcome == TaskOutcome.SUCCESS

        when:
        result = runWithArguments('generateJooq')

        then:
        result.task(':generateJooq').outcome == TaskOutcome.SUCCESS
    }

    void "participates in incremental build if explicitly configured"() {
        given:
        buildFile << buildWithJooqPluginDSL()
        buildFile << ALL_INPUTS_DECLARED_JOOQ_TASK

        when:
        def result = runWithArguments('generateJooq')

        then:
        result.task(':generateJooq').outcome == TaskOutcome.SUCCESS

        when:
        result = runWithArguments('generateJooq')

        then:
        result.task(':generateJooq').outcome == TaskOutcome.UP_TO_DATE
    }

    void "detects when jOOQ configuration is different"() {
        given:
        buildFile << buildWithJooqPluginDSL()

        when:
        def result = runWithArguments('generateJooq')

        then:
        result.task(':generateJooq').outcome == TaskOutcome.SUCCESS

        when:
        buildFile.delete()
        buildFile << buildWithJooqPluginDSL('different.target.package.name')

        result = runWithArguments('build')

        then:
        result.task(':generateJooq').outcome == TaskOutcome.SUCCESS
    }

    void "detects when parts of the outputs are removed"() {
        given:
        buildFile << buildWithJooqPluginDSL()

        when:
        def result = runWithArguments('generateJooq')

        then:
        fileExists('build/generated-src/jooq/main/nu/studer/sample/jooq_test/tables/Foo.java')
        result.task(':generateJooq').outcome == TaskOutcome.SUCCESS

        when:
        new File(workspaceDir, 'build/generated-src/jooq/main/nu/studer/sample/jooq_test/tables/Foo.java').delete()
        result = runWithArguments('generateJooq')

        then:
        result.task(':generateJooq').outcome == TaskOutcome.SUCCESS
    }

    void "cleans output before each run"() {
        given:
        buildFile << buildWithJooqPluginDSL()

        when:
        def result = runWithArguments('generateJooq')

        then:
        fileExists('build/generated-src/jooq/main/nu/studer/sample/jooq_test/tables/Foo.java')
        result.task(':generateJooq').outcome == TaskOutcome.SUCCESS

        when:
        buildFile.delete()
        buildFile << buildWithJooqPluginDSL('different.target.pkg.name')

        result = runWithArguments('build')

        then:
        fileExists('build/generated-src/jooq/main/different/target/pkg/name/jooq_test/tables/Foo.java')
        !fileExists('build/generated-src/jooq/main/nu/studer/sample/jooq_test/tables/Foo.java')
        result.task(':generateJooq').outcome == TaskOutcome.SUCCESS
    }

    void "throws error when cleaning of output is set to false in the jOOQ configuration"() {
        given:
        buildFile << buildWithJooqPluginDSL()
        buildFile << """
jooq.configurations.main.jooqConfiguration.generator.target.clean = false
"""

        when:
        def result = runAndFailWithArguments('generateJooq')

        then:
        result.output.contains "generator.target.clean must not be set to false. Disabling the cleaning of the output directory can lead to unexpected behavior in a Gradle build."
    }

    void "task output is cacheable if jooq generate task is marked as cacheable and all inputs declared"() {
        given:
        buildFile << buildWithJooqPluginDSL()
        buildFile << ALL_INPUTS_DECLARED_JOOQ_TASK
        buildFile << CACHE_JOOQ_TASK

        when:
        def result = runWithArguments('generateJooq', '--build-cache')

        then:
        result.task(':generateJooq').outcome == TaskOutcome.SUCCESS

        when:
        result = runWithArguments('cleanGenerateJooq', 'generateJooq', '--build-cache')

        then:
        result.task(':generateJooq').outcome == TaskOutcome.FROM_CACHE
    }

    void "task inputs are relocatable if jooq generate task is marked as cacheable and all inputs declared"() {
        given:
        buildFile << buildWithJooqPluginDSL(null, new File(workspaceDir, 'src/generated/jooq').absolutePath)
        buildFile << ALL_INPUTS_DECLARED_JOOQ_TASK
        buildFile << CACHE_JOOQ_TASK

        and:
        def otherProject = 'other'
        def otherSettingsFile = file("$otherProject/settings.gradle")
        Files.copy(settingsFile.toPath(), otherSettingsFile.toPath(), StandardCopyOption.REPLACE_EXISTING)

        def otherBuildFile = file("$otherProject/build.gradle")
        otherBuildFile << buildWithJooqPluginDSL(null, new File(workspaceDir, "$otherProject/src/generated/jooq").absolutePath)
        otherBuildFile << ALL_INPUTS_DECLARED_JOOQ_TASK
        otherBuildFile << CACHE_JOOQ_TASK

        when:
        def result = gradleRunner('generateJooq', '--build-cache')
            .withProjectDir(workspaceDir)
            .build()

        then:
        result.task(':generateJooq').outcome == TaskOutcome.SUCCESS

        when:
        result = gradleRunner('generateJooq', '--build-cache')
            .withProjectDir(new File(workspaceDir, otherProject))
            .build()

        then:
        result.task(':generateJooq').outcome == TaskOutcome.FROM_CACHE
    }

    void "does not clean sources generated by jooq as part of Gradle's clean life-cycle task"() {
        given:
        buildFile << buildWithJooqPluginDSL(null, 'src/generated/jooq')

        when:
        runWithArguments('generateJooq')

        then:
        fileExists('src/generated/jooq/nu/studer/sample/jooq_test/tables/Foo.java')

        when:
        def result = runWithArguments('clean')

        then:
        fileExists('src/generated/jooq/nu/studer/sample/jooq_test/tables/Foo.java')
        !result.task(':cleanGenerateJooq')
    }

    void "cleans sources by calling clean task rule"() {
        given:
        buildFile << buildWithJooqPluginDSL('nu.studer.sample', 'src/generated/jooq/main')

        when:
        runWithArguments('build')

        then:
        fileExists('src/generated/jooq/main/nu/studer/sample/jooq_test/tables/Foo.java')

        when:
        def result = runWithArguments('cleanGenerateJooq')

        then:
        !fileExists('src/generated/jooq/main/nu/studer/sample/jooq_test/tables/Foo.java')
        result.task(':cleanGenerateJooq').outcome == TaskOutcome.SUCCESS
    }

    void "can normalize jooq configuration input property"() {
        given:
        def packageIgnoringConfigNormalization = """
generateJooq {
  generationToolNormalization = { org.jooq.meta.jaxb.Configuration c ->
    c.generator.target.packageName = ''
  }
}
"""

        when: // apply normalization
        buildFile << buildWithJooqPluginDSL('some.place')
        buildFile << ALL_INPUTS_DECLARED_JOOQ_TASK
        buildFile << packageIgnoringConfigNormalization
        def result = runWithArguments('generateJooq')

        then:
        fileExists('build/generated-src/jooq/main/some/place/jooq_test/tables/Foo.java')
        result.task(':generateJooq').outcome == TaskOutcome.SUCCESS

        when: // apply normalization
        buildFile.delete()
        buildFile << buildWithJooqPluginDSL('some.other.place')
        buildFile << ALL_INPUTS_DECLARED_JOOQ_TASK
        buildFile << packageIgnoringConfigNormalization
        result = runWithArguments('generateJooq')

        then:
        result.task(':generateJooq').outcome == TaskOutcome.UP_TO_DATE

        when: // do not apply normalization
        buildFile.delete()
        buildFile << buildWithJooqPluginDSL('yet.another.place')
        buildFile << ALL_INPUTS_DECLARED_JOOQ_TASK
        result = runWithArguments('generateJooq')

        then:
        fileExists('build/generated-src/jooq/main/yet/another/place/jooq_test/tables/Foo.java')
        result.task(':generateJooq').outcome == TaskOutcome.SUCCESS
    }

    void "can customize java execution and handle execution result"() {
        given:
        buildFile << buildWithJooqPluginDSL()
        buildFile << """
generateJooq {
  def out = new ByteArrayOutputStream()
  javaExecSpec = { JavaExecSpec s ->
    s.standardOutput = out
    s.errorOutput = out
    s.ignoreExitValue = true
  }
  execResultHandler = { ExecResult r ->
    if (r.exitValue == 0) {
      println('Jooq source code generation succeeded')
    }
  }
}
"""

        when:
        def result = runWithArguments('generateJooq')

        then:
        fileExists('build/generated-src/jooq/main/nu/studer/sample/jooq_test/tables/Foo.java')
        result.output.contains('Jooq source code generation succeeded')
        result.task(':generateJooq').outcome == TaskOutcome.SUCCESS
    }

    void "shows an error message with a link to the current XSD when a property is missing"() {
        given:
        buildFile << buildWithMissingProperty()

        when:
        def result = runAndFailWithArguments('generateJooq')

        then:
        result.output.contains "Invalid property: 'missing' on extension 'jooq.main.generationTool.generator.generate', value: true. Please check the current XSD: https://www.jooq.org/xsd/${Constants.XSD_CODEGEN}"
    }

    void "shows an error message with a link to the current XSD when a configuration container element is missing"() {
        given:
        buildFile << buildWithMissingConfigurationContainerElement()

        when:
        def result = runAndFailWithArguments('generateJooq')

        then:
        result.output.contains "Invalid configuration container element: 'missing' on extension 'jooq.main.generationTool'. Please check the current XSD: https://www.jooq.org/xsd/${Constants.XSD_CODEGEN}"
    }

    void "correctly writes out boolean default values"() {
        given:
        buildFile << buildWithJooqPluginDSL()

        when:
        def result = runWithArguments('generateJooq')

        then:
        def configXml = new File(workspaceDir, 'build/tmp/generateJooq/config.xml')
        configXml.exists()

        def rootNode = new XmlSlurper().parse(configXml)
        rootNode.generator.generate.globalTableReferences == true
        rootNode.generator.generate.emptySchemas == false

        and:
        result.task(':generateJooq').outcome == TaskOutcome.SUCCESS
    }

    void "successfully applies custom strategies when a sub project is added to the jooqGenerator configuration"() {
        given:
        buildFile << buildWithCustomStrategiesOnSubProject()
        settingsFile << "include 'custom-generator'"
        file('custom-generator/build.gradle') << customGeneratorBuildFile()
        file('custom-generator/src/main/java/nu/studer/sample/SampleGeneratorStrategy.java') << customGeneratorStrategyClass()

        when:
        def result = runWithArguments('build')

        then:
        result.task(':generateJooq').outcome == TaskOutcome.SUCCESS

        and:
        def foo = new File(workspaceDir, 'build/generated-src/jooq/main/org/jooq/generated/jooq_test/tables/records/FooRecord.java')
        foo.exists()
        foo.text.contains("public Integer A() {") // instead of getA, as the sample generator strategy removed the get prefix
    }

    void "accepts matcher strategies even when name is not explicitly set to null"() {
        given:
        buildFile << buildWithMatcherStrategies()

        when:
        def result = runWithArguments('build')

        then:
        result.task(':generateJooq').outcome == TaskOutcome.SUCCESS
    }

    void "accepts empty JDBC configuration even when not explicitly set to null"() {
        given:
        def sqlSchemaFile = file('src/main/resources/schema.sql')
        sqlSchemaFile << 'CREATE TABLE jooq_test.foo (a INT);'
        buildFile << buildWithNoJdbc(sqlSchemaFile)

        when:
        def result = runWithArguments('build')

        then:
        result.task(':generateJooq').outcome == TaskOutcome.SUCCESS
    }

    void "parses DSL with variables and methods references"() {
        given:
        buildFile << buildWithVariablesAndMethods()

        when:
        def result = runWithArguments('build')

        then:
        result.task(':generateJooq').outcome == TaskOutcome.SUCCESS
    }

    private static String buildWithJooqPluginDSL(String targetPackageName = null,
                                                 String targetDirectory = null,
                                                 Boolean generateSchemaSourceOnCompilation = null,
                                                 String jooqVersion = null,
                                                 JooqEdition jooqEdition = null) {
        """
plugins {
    id 'nu.studer.jooq'
}

apply plugin: 'java'

repositories {
    jcenter()
}

dependencies {
    jooqGenerator 'com.h2database:h2:1.4.200'
}

jooq {
  ${jooqVersion != null ? "version = '$jooqVersion'" : ""}
  ${jooqEdition != null ? "edition = nu.studer.gradle.jooq.JooqEdition.${jooqEdition.name()}" : ""}
  configurations {
    main {
      ${generateSchemaSourceOnCompilation != null ? "generateSchemaSourceOnCompilation = $generateSchemaSourceOnCompilation" : ""}
      generationTool {
        logging = org.jooq.meta.jaxb.Logging.WARN
        jdbc {
          driver = 'org.h2.Driver'
          url = 'jdbc:h2:~/test;AUTO_SERVER=TRUE'
          user = 'sa'
          password = ''
        }
        generator {
          name = 'org.jooq.codegen.DefaultGenerator'
          strategy {
            name = 'org.jooq.codegen.DefaultGeneratorStrategy'
          }
          database {
            name = 'org.jooq.meta.h2.H2Database'
            includes = '.*'
            excludes = ''
            forcedTypes {
              forcedType {
                name = 'varchar'
                expression = '.*'
                types = 'JSONB?'
              }
              forcedType {
                name = 'varchar'
                expression = '.*'
                types = 'INET'
              }
            }
          }
          generate {
            javaTimeTypes = true
          }
          target {
            ${targetPackageName != null ? "packageName = '$targetPackageName'" : "packageName = 'nu.studer.sample'"}
            ${targetDirectory != null ? "directory = '$targetDirectory'" : ""}
          }
        }
      }
    }
  }
}
"""
    }

    private static String buildWithMultipleItemsJooqPluginDSL() {
        """
plugins {
    id 'nu.studer.jooq'
}

apply plugin: 'java'

repositories {
    jcenter()
}

dependencies {
    jooqGenerator 'com.h2database:h2:1.4.200'
}

jooq {
  version = '3.14.7'
  edition = nu.studer.gradle.jooq.JooqEdition.OSS
  configurations {
    main {
      generationTool {
        logging = org.jooq.meta.jaxb.Logging.WARN
        jdbc {
          driver = 'org.h2.Driver'
          url = 'jdbc:h2:~/test;AUTO_SERVER=TRUE'
          user = 'sa'
          password = ''
        }
        generator {
          database {
            name = 'org.jooq.meta.h2.H2Database'
          }
          target {
            packageName = 'nu.studer.main-pkg'
          }
        }
      }
    }
    sample {
      generationTool {
        logging = org.jooq.meta.jaxb.Logging.WARN
        jdbc {
          driver = 'org.h2.Driver'
          url = 'jdbc:h2:~/test;AUTO_SERVER=TRUE'
          user = 'sa'
          password = ''
        }
        generator {
          database {
            name = 'org.jooq.meta.h2.H2Database'
          }
          target {
            packageName = 'nu.studer.sample-pkg'
          }
        }
      }
    }
  }
}
"""
    }

    private static String buildWithMissingProperty() {
        """
plugins {
    id 'nu.studer.jooq'
}

apply plugin: 'java'

repositories {
    jcenter()
}

dependencies {
    jooqGenerator 'com.h2database:h2:1.4.200'
}

jooq {
  configurations {
    main {
      generationTool {
        logging = org.jooq.meta.jaxb.Logging.WARN
        jdbc {
          driver = 'org.h2.Driver'
          url = 'jdbc:h2:~/test;AUTO_SERVER=TRUE'
          user = 'sa'
          password = ''
        }
        generator {
          name = 'org.jooq.codegen.DefaultGenerator'
          generate {
            missing = true
          }
        }
      }
    }
  }
}
"""
    }

    private static String buildWithMissingConfigurationContainerElement() {
        """
plugins {
    id 'nu.studer.jooq'
}

apply plugin: 'java'

repositories {
    jcenter()
}

dependencies {
    jooqGenerator 'com.h2database:h2:1.4.200'
}

jooq {
  configurations {
    main {
      generationTool {
        logging = org.jooq.meta.jaxb.Logging.WARN
        jdbc {
          driver = 'org.h2.Driver'
          url = 'jdbc:h2:~/test;AUTO_SERVER=TRUE'
          user = 'sa'
          password = ''
        }
        missing {
        }
      }
    }
  }
}
"""
    }

    private static String buildWithCustomStrategiesOnSubProject() {
        """
plugins {
    id 'nu.studer.jooq'
}

apply plugin: 'java'

repositories {
    jcenter()
}

dependencies {
    jooqGenerator 'com.h2database:h2:1.4.200'
    jooqGenerator project(':custom-generator')  // include sub-project that contains the custom generator strategy
}

jooq {
  configurations {
    main {
      generationTool {
        logging = org.jooq.meta.jaxb.Logging.WARN
        jdbc {
          driver = 'org.h2.Driver'
          url = 'jdbc:h2:~/test;AUTO_SERVER=TRUE'
          user = 'sa'
          password = ''
        }
        generator {
          name = 'org.jooq.codegen.DefaultGenerator'
          strategy {
            name = 'nu.studer.sample.SampleGeneratorStrategy'  // use the custom generator strategy
          }
          database {
            name = 'org.jooq.meta.h2.H2Database'
          }
          generate {
            javaTimeTypes = true
          }
        }
      }
    }
  }
}
"""
    }

    private static def customGeneratorBuildFile() {
        """
apply plugin: 'java'

repositories {
    jcenter()
}

dependencies {
    implementation 'org.jooq:jooq-codegen:3.14.7'
}
"""
    }

    private static String customGeneratorStrategyClass() {
        """
package nu.studer.sample;

import org.jooq.codegen.DefaultGeneratorStrategy;
import org.jooq.meta.Definition;

public final class SampleGeneratorStrategy extends DefaultGeneratorStrategy {

    @Override
    public String getJavaGetterName(Definition definition, Mode mode) {
        // do not prefix getters with 'get'
        return super.getJavaGetterName(definition, mode).substring("get".length());
    }

}
"""
    }

    private static String buildWithMatcherStrategies() {
        """
plugins {
    id 'nu.studer.jooq'
}

apply plugin: 'java'

repositories {
    jcenter()
}

dependencies {
    jooqGenerator 'com.h2database:h2:1.4.200'
}

jooq {
  configurations {
    main {
      generationTool {
        logging = org.jooq.meta.jaxb.Logging.WARN
        jdbc {
          driver = 'org.h2.Driver'
          url = 'jdbc:h2:~/test;AUTO_SERVER=TRUE'
          user = 'sa'
          password = ''
        }
        generator {
          strategy {
            matchers {
              tables {
                table {
                  pojoClass {
                    transform = 'PASCAL'
                    expression = '\$0_POJO'
                  }
                }
              }
            }
          }
          database {
            name = 'org.jooq.meta.h2.H2Database'
          }
          generate {
            javaTimeTypes = true
          }
        }
      }
    }
  }
}
"""
    }

    private static String buildWithNoJdbc(def sqlFile) {
        """
plugins {
    id 'nu.studer.jooq'
}

apply plugin: 'java'

repositories {
    jcenter()
}

dependencies {
    jooqGenerator 'org.jooq:jooq-meta-extensions'
}

jooq {
  configurations {
    main {
      generationTool {
        logging = org.jooq.meta.jaxb.Logging.WARN
        generator {
          database {
            name = 'org.jooq.meta.extensions.ddl.DDLDatabase'
            properties {
              property {
                key = 'scripts'
                value = '$sqlFile.absolutePath'
              }
            }
          }
        }
      }
    }
  }
}
"""
    }

    private static String buildWithVariablesAndMethods() {
        """
plugins {
    id 'nu.studer.jooq'
}

apply plugin: 'java'

repositories {
    jcenter()
}

dependencies {
    jooqGenerator 'com.h2database:h2:1.4.200'
}

def userSA = 'sa'

def calculateDriver() {
    'org.h2.Driver'
}

jooq {
  configurations {
    main {
      generationTool {
        logging = org.jooq.meta.jaxb.Logging.WARN
        jdbc {
          driver = calculateDriver()
          url = 'jdbc:h2:~/test;AUTO_SERVER=TRUE'
          user = userSA
          password = ''
        }
        generator {
          name = 'org.jooq.codegen.DefaultGenerator'
          strategy {
            name = 'org.jooq.codegen.DefaultGeneratorStrategy'
          }
          database {
            name = 'org.jooq.meta.h2.H2Database'
            includes = '.*'
            excludes = ''
          }
          generate {
            javaTimeTypes = true
          }
        }
      }
    }
  }
}
"""
    }

    private boolean fileExists(String filePath) {
        def file = new File(workspaceDir, filePath)
        file.exists() && file.file
    }

}
