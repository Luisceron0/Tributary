package com.tributary.domain;

import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;

/** Entry point for the Gherkin scenarios under {@code src/test/resources/features/}. */
@Suite
@IncludeEngines("cucumber")
@SelectPackages("features")
class RunCucumberTest {}
