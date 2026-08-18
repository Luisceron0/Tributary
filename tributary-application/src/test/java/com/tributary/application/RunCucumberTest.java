package com.tributary.application;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;

/**
 * Entry point for this module's Gherkin scenarios under {@code src/test/resources/features/}.
 * Glue is declared explicitly because the step definitions live beside the use cases they exercise
 * ({@code com.tributary.application.usecase}), not under the feature package.
 */
@Suite
@IncludeEngines("cucumber")
@SelectPackages("features")
@ConfigurationParameter(key = "cucumber.glue", value = "com.tributary.application.usecase")
class RunCucumberTest {}
