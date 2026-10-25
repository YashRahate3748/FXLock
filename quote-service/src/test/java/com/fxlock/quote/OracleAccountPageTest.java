package com.fxlock.quote;

import org.springframework.test.context.ActiveProfiles;

/** Same MVC/security contract with oracle-local, without loading a datasource or contacting Oracle. */
@ActiveProfiles(profiles = "oracle-local", inheritProfiles = false)
class OracleAccountPageTest extends AccountPageTest {}
