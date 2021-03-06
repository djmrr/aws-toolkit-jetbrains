// Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.lambda.execution.local

import com.intellij.execution.ExecutionException
import com.intellij.execution.ExecutorRegistry
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.runInEdtAndWait
import com.nhaarman.mockitokotlin2.doReturn
import com.nhaarman.mockitokotlin2.mock
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.services.lambda.model.Runtime
import software.aws.toolkits.core.rules.EnvironmentVariableHelper
import software.aws.toolkits.jetbrains.core.credentials.MockCredentialsManager
import software.aws.toolkits.jetbrains.settings.SamExecutableDetector
import software.aws.toolkits.jetbrains.settings.SamSettings
import software.aws.toolkits.jetbrains.utils.rules.JavaCodeInsightTestFixtureRule
import software.aws.toolkits.jetbrains.utils.toElement
import software.aws.toolkits.resources.message

class LocalLambdaRunConfigurationTest {
    @Rule
    @JvmField
    val projectRule = JavaCodeInsightTestFixtureRule()

    @Rule
    @JvmField
    val envHelper = EnvironmentVariableHelper()

    @Rule
    @JvmField
    val tempDir = TemporaryFolder()

    private val mockId = "MockCredsId"
    private val mockCreds = AwsBasicCredentials.create("Access", "ItsASecret")

    @Before
    fun setUp() {
        SamSettings.getInstance().savedExecutablePath = "sam"
        MockCredentialsManager.getInstance().addCredentials(mockId, mockCreds)

        projectRule.fixture.addClass(
            """
            package com.example;

            public class LambdaHandler {
                public String handleRequest(String request) {
                    return request.toUpperCase();
                }
            }
            """
        )
    }

    @After
    fun tearDown() {
        MockCredentialsManager.getInstance().reset()
    }

    @Test
    fun samIsNotSet() {
        SamSettings.getInstance().savedExecutablePath = null
        envHelper.remove("PATH")
        assumeTrue(SamExecutableDetector().detect() == null)

        runInEdtAndWait {
            val runConfiguration = createHandlerBasedRunConfiguration(
                project = projectRule.project,
                credentialsProviderId = mockId
            )
            assertThat(runConfiguration).isNotNull
            assertThatThrownBy { runConfiguration.checkConfiguration() }
                .isInstanceOf(RuntimeConfigurationError::class.java)
                .hasMessageContaining("Invalid SAM CLI executable configured:")
        }
    }

    @Test
    fun handlerIsNotSet() {
        runInEdtAndWait {
            val runConfiguration = createHandlerBasedRunConfiguration(
                project = projectRule.project,
                handler = null,
                credentialsProviderId = mockId
            )
            assertThat(runConfiguration).isNotNull
            assertThatThrownBy { getState(runConfiguration) }
                .isInstanceOf(ExecutionException::class.java)
                .hasMessage(message("lambda.run_configuration.no_handler_specified"))
        }
    }

    @Test
    fun runtimeIsNotSet() {
        runInEdtAndWait {
            val runConfiguration = createHandlerBasedRunConfiguration(
                project = projectRule.project,
                runtime = null,
                credentialsProviderId = mockId
            )
            assertThat(runConfiguration).isNotNull
            assertThatThrownBy { getState(runConfiguration) }
                .isInstanceOf(ExecutionException::class.java)
                .hasMessage(message("lambda.run_configuration.no_runtime_specified"))
        }
    }

    @Test
    fun handlerDoesNotExist() {
        runInEdtAndWait {
            val runConfiguration = createHandlerBasedRunConfiguration(
                project = projectRule.project,
                handler = "Fake",
                credentialsProviderId = mockId
            )
            assertThat(runConfiguration).isNotNull
            assertThatThrownBy { getState(runConfiguration) }
                .isInstanceOf(ExecutionException::class.java)
                .hasMessage(message("lambda.run_configuration.handler_not_found", "Fake"))
        }
    }

    @Test
    fun templateFileNotSet() {
        runInEdtAndWait {
            val runConfiguration = createTemplateRunConfiguration(
                project = projectRule.project,
                templateFile = null,
                credentialsProviderId = mockId
            )
            assertThat(runConfiguration).isNotNull
            assertThatThrownBy { getState(runConfiguration) }
                .isInstanceOf(ExecutionException::class.java)
                .hasMessage(message("lambda.run_configuration.sam.no_template_specified"))
        }
    }

    @Test
    fun logicalFunctionNotSet() {
        runInEdtAndWait {
            val runConfiguration = createTemplateRunConfiguration(
                project = projectRule.project,
                templateFile = "test",
                logicalId = null,
                credentialsProviderId = mockId
            )
            assertThat(runConfiguration).isNotNull
            assertThatThrownBy { getState(runConfiguration) }
                .isInstanceOf(ExecutionException::class.java)
                .hasMessage(message("lambda.run_configuration.sam.no_function_specified"))
        }
    }

    @Test
    fun functionDoesNotExist() {
        runInEdtAndWait {
            val template = tempDir.newFile("template.yaml").also {
                it.writeText(
                    """
                Resources:
                  SomeFunction:
                    Type: AWS::Serverless::Function
                    Properties:
                      Handler: com.example.LambdaHandler::handleRequest
                      CodeUri: /some/dummy/code/location
                      Runtime: java8
                      Timeout: 900
                """.trimIndent()
                )
            }.absolutePath
            val logicalName = "NotSomeFunction"

            val runConfiguration = createTemplateRunConfiguration(
                project = projectRule.project,
                templateFile = template,
                logicalId = logicalName,
                credentialsProviderId = mockId
            )
            assertThat(runConfiguration).isNotNull
            assertThatThrownBy { getState(runConfiguration) }
                .isInstanceOf(ExecutionException::class.java)
                .hasMessage(message("lambda.run_configuration.sam.no_such_function", logicalName, template))
        }
    }

    @Test
    fun unsupportedRuntime() {
        runInEdtAndWait {
            val template = tempDir.newFile("template.yaml").also {
                it.writeText(
                    """
                Resources:
                  SomeFunction:
                    Type: AWS::Serverless::Function
                    Properties:
                      Handler: com.example.LambdaHandler::handleRequest
                      CodeUri: /some/dummy/code/location
                      Runtime: FAKE
                      Timeout: 900
                """.trimIndent()
                )
            }.absolutePath
            val logicalName = "SomeFunction"

            val runConfiguration = createTemplateRunConfiguration(
                project = projectRule.project,
                templateFile = template,
                logicalId = logicalName,
                credentialsProviderId = mockId
            )
            assertThat(runConfiguration).isNotNull
            assertThatThrownBy { getState(runConfiguration) }
                .isInstanceOf(ExecutionException::class.java)
                .hasMessage(message("lambda.run_configuration.no_runtime_specified", logicalName, template))
        }
    }

    @Test
    fun invalidRegion() {
        runInEdtAndWait {
            val runConfiguration = createHandlerBasedRunConfiguration(
                project = projectRule.project,
                region = null,
                credentialsProviderId = mockId
            )
            assertThat(runConfiguration).isNotNull
            assertThatThrownBy { getState(runConfiguration) }
                .isInstanceOf(ExecutionException::class.java)
                .hasMessage(message("lambda.run_configuration.no_region_specified"))
        }
    }

    @Test
    fun noCredentials() {
        runInEdtAndWait {
            val runConfiguration = createHandlerBasedRunConfiguration(
                project = projectRule.project,
                credentialsProviderId = null
            )
            assertThat(runConfiguration).isNotNull
            assertThatThrownBy { getState(runConfiguration) }
                .isInstanceOf(ExecutionException::class.java)
                .hasMessage(message("lambda.run_configuration.no_credentials_specified"))
        }
    }

    @Test
    fun invalidCredentials() {
        val credentialName = "DNE"
        runInEdtAndWait {
            val runConfiguration = createHandlerBasedRunConfiguration(
                project = projectRule.project,
                credentialsProviderId = credentialName
            )
            assertThat(runConfiguration).isNotNull
            assertThatThrownBy { getState(runConfiguration) }
                .isInstanceOf(ExecutionException::class.java)
                .hasMessage(message("lambda.run_configuration.credential_not_found_error", credentialName))
        }
    }

    @Test
    fun inputTextIsResolved() {
        runInEdtAndWait {
            val runConfiguration = createHandlerBasedRunConfiguration(
                project = projectRule.project,
                input = "TestInput",
                credentialsProviderId = mockId
            )
            assertThat(runConfiguration).isNotNull
            assertThat(getState(runConfiguration).settings.input).isEqualTo("TestInput")
        }
    }

    @Test
    fun inputFileIsResolved() {
        val tempFile = FileUtil.createTempFile("temp", ".json")
        tempFile.writeText("TestInputFile")

        runInEdtAndWait {
            val runConfiguration = createHandlerBasedRunConfiguration(
                project = projectRule.project,
                input = tempFile.absolutePath,
                inputIsFile = true,
                credentialsProviderId = mockId
            )
            assertThat(runConfiguration).isNotNull
            assertThat(getState(runConfiguration).settings.input).isEqualTo("TestInputFile")
        }
    }

    @Test
    fun readExternalHandlerBasedDoesNotThrowException() {
        // This tests for backwards compatibility, data should not be changed except in backwards compatible ways
        val element = """
            <configuration name="HelloWorldFunction" type="aws.lambda" factoryName="Local" temporary="true" nameIsGenerated="true">
              <option name="credentialProviderId" value="profile:default" />
              <option name="environmentVariables">
                <map>
                  <entry key="Foo" value="Bar" />
                </map>
              </option>
              <option name="handler" value="helloworld.App::handleRequest" />
              <option name="input" value="&quot;&quot;" />
              <option name="inputIsFile" value="false" />
              <option name="logicalFunctionName" />
              <option name="regionId" value="us-west-2" />
              <option name="runtime" value="python3.6" />
              <option name="templateFile" />
              <option name="useTemplate" value="false" />
              <method v="2" />
            </configuration>
        """.toElement()

        runInEdtAndWait {
            val runConfiguration = samRunConfiguration(projectRule.project)

            runConfiguration.readExternal(element)

            assertThat(runConfiguration.isUsingTemplate()).isFalse()
            assertThat(runConfiguration.templateFile()).isNull()
            assertThat(runConfiguration.logicalId()).isNull()
            assertThat(runConfiguration.handler()).isEqualTo("helloworld.App::handleRequest")
            assertThat(runConfiguration.runtime()).isEqualTo(Runtime.PYTHON3_6)
            assertThat(runConfiguration.environmentVariables()).containsAllEntriesOf(mapOf("Foo" to "Bar"))
            assertThat(runConfiguration.regionId()).isEqualTo("us-west-2")
            assertThat(runConfiguration.credentialProviderId()).isEqualTo("profile:default")
        }
    }

    @Test
    fun readExternalTemplateBasedDoesNotThrowException() {
        // This tests for backwards compatibility, data should not be changed except in backwards compatible ways
        val element = """
                <configuration name="HelloWorldFunction" type="aws.lambda" factoryName="Local" temporary="true" nameIsGenerated="true">
                  <option name="credentialProviderId" value="profile:default" />
                  <option name="environmentVariables">
                    <map>
                      <entry key="Foo" value="Bar" />
                    </map>
                  </option>
                  <option name="handler" />
                  <option name="input" value="&quot;&quot;" />
                  <option name="inputIsFile" value="false" />
                  <option name="logicalFunctionName" value="HelloWorldFunction" />
                  <option name="regionId" value="us-west-2" />
                  <option name="runtime" />
                  <option name="templateFile" value="template.yaml" />
                  <option name="useTemplate" value="true" />
                  <method v="2" />
                </configuration>
        """.toElement()

        runInEdtAndWait {
            val runConfiguration = samRunConfiguration(projectRule.project)

            runConfiguration.readExternal(element)

            assertThat(runConfiguration.isUsingTemplate()).isTrue()
            assertThat(runConfiguration.templateFile()).isEqualTo("template.yaml")
            assertThat(runConfiguration.logicalId()).isEqualTo("HelloWorldFunction")
            assertThat(runConfiguration.handler()).isNull()
            assertThat(runConfiguration.runtime()).isNull()
            assertThat(runConfiguration.environmentVariables()).containsAllEntriesOf(mapOf("Foo" to "Bar"))
            assertThat(runConfiguration.regionId()).isEqualTo("us-west-2")
            assertThat(runConfiguration.credentialProviderId()).isEqualTo("profile:default")
        }
    }

    @Test
    fun readInputFileBasedDoesNotThrowException() {
        // This tests for backwards compatibility, data should not be changed except in backwards compatible ways
        val element = """
                <configuration name="HelloWorldFunction" type="aws.lambda" factoryName="Local" temporary="true" nameIsGenerated="true">
                  <option name="credentialProviderId" value="profile:default" />
                  <option name="environmentVariables">
                    <map>
                      <entry key="Foo" value="Bar" />
                    </map>
                  </option>
                  <option name="handler" />
                  <option name="input" value="event.json" />
                  <option name="inputIsFile" value="true" />
                  <option name="logicalFunctionName" value="HelloWorldFunction" />
                  <option name="regionId" value="us-west-2" />
                  <option name="runtime" />
                  <option name="templateFile" value="template.yaml" />
                  <option name="useTemplate" value="true" />
                  <method v="2" />
                </configuration>
        """.toElement()

        runInEdtAndWait {
            val runConfiguration = samRunConfiguration(projectRule.project)

            runConfiguration.readExternal(element)

            assertThat(runConfiguration.isUsingInputFile()).isTrue()
            assertThat(runConfiguration.inputSource()).isEqualTo("event.json")
        }
    }

    @Test
    fun readInputTextBasedDoesNotThrowException() {
        // This tests for backwards compatibility, data should not be changed except in backwards compatible ways
        val element = """
                <configuration name="HelloWorldFunction" type="aws.lambda" factoryName="Local" temporary="true" nameIsGenerated="true">
                  <option name="credentialProviderId" value="profile:default" />
                  <option name="environmentVariables">
                    <map>
                      <entry key="Foo" value="Bar" />
                    </map>
                  </option>
                  <option name="handler" />
                  <option name="input" value="{}" />
                  <option name="inputIsFile" value="false" />
                  <option name="logicalFunctionName" value="HelloWorldFunction" />
                  <option name="regionId" value="us-west-2" />
                  <option name="runtime" />
                  <option name="templateFile" value="template.yaml" />
                  <option name="useTemplate" value="true" />
                  <method v="2" />
                </configuration>
        """.toElement()

        runInEdtAndWait {
            val runConfiguration = samRunConfiguration(projectRule.project)

            runConfiguration.readExternal(element)

            assertThat(runConfiguration.isUsingInputFile()).isFalse()
            assertThat(runConfiguration.inputSource()).isEqualTo("{}")
        }
    }

    @Test
    fun readSamSettings() {
        // This tests for backwards compatibility, data should not be changed except in backwards compatible ways
        val element = """
                <configuration name="HelloWorldFunction" type="aws.lambda" factoryName="Local" temporary="true" nameIsGenerated="true">
                  <option name="credentialProviderId" value="profile:default" />
                  <option name="environmentVariables">
                    <map>
                      <entry key="Foo" value="Bar" />
                    </map>
                  </option>
                  <option name="handler" />
                  <option name="input" value="{}" />
                  <option name="inputIsFile" value="false" />
                  <option name="logicalFunctionName" value="HelloWorldFunction" />
                  <option name="regionId" value="us-west-2" />
                  <option name="runtime" />
                  <option name="templateFile" value="template.yaml" />
                  <option name="useTemplate" value="true" />
                  <sam>
                    <option name="buildInContainer" value="true" />
                    <option name="dockerNetwork" value="aws-lambda" />
                    <option name="skipImagePull" value="true" />
                  </sam>
                  <method v="2" />
                </configuration>
        """.toElement()

        runInEdtAndWait {
            val runConfiguration = samRunConfiguration(projectRule.project)

            runConfiguration.readExternal(element)

            assertThat(runConfiguration.skipPullImage()).isTrue()
            assertThat(runConfiguration.buildInContainer()).isTrue()
            assertThat(runConfiguration.dockerNetwork()).isEqualTo("aws-lambda")
        }
    }

    private fun getState(runConfiguration: LocalLambdaRunConfiguration): SamRunningState {
        val executor = ExecutorRegistry.getInstance().getExecutorById(DefaultRunExecutor.EXECUTOR_ID)
        val environmentMock = mock<ExecutionEnvironment> {
            on { project } doReturn projectRule.project
            on { getExecutor() } doReturn executor
        }
        return runConfiguration.getState(executor, environmentMock)
    }
}