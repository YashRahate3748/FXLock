# Fixing the errors in Spring Tools / Eclipse

The screenshots show two independent problems:

1. The module POMs lacked xsi:schemaLocation. This caused the XML schema/grammar warnings. All four POMs now have the standard Maven schema declaration, and each child explicitly references ../pom.xml.
2. Maven could not download spring-boot-starter-parent:4.1.2. Adding an XML schema does not fix network resolution. The earlier CLI build on this machine exposed a configured proxy named tw-proxy-hyd.oraclecorp.com that could not resolve. The screenshot cuts off the transfer failure's cause, so the exact STS transfer error is not independently confirmed. The provided direct settings bypass that configured Maven proxy/mirror for this project. Boot 4.1.2 was successfully resolved and compiled previously; keep the supplied dependency versions.

## Apply the corrected files

Extract the revised ZIP to a new directory, keeping this structure:

```text
member4/
  pom.xml                    # com.example:fx-member4
  config/maven-direct.xml
  eureka-server/pom.xml      # parent: ../pom.xml
  event-contract/pom.xml
  event-service/pom.xml
```

The names displayed as separate projects in Eclipse do not imply the folders are siblings on disk. Keep the actual extracted nesting. If importing into the same workspace, remove the previous project entries first **without selecting Delete project contents on disk**, or use a fresh Eclipse workspace. Do not import only eureka-server.

1. In Spring Tools, choose File > Import > Maven > Existing Maven Projects.
2. Choose the extracted member4 directory containing the root pom.xml. Select all four detected Maven projects and finish.
3. Configure JDK 25 under Window > Preferences > Java > Installed JREs. Set the project Java runtime to JDK 25. Configure the Maven run configuration to use that JDK too.

## Fix Maven resolution in Spring Tools

1. Open Window > Preferences > Maven > User Settings.
2. For this direct-network setup, select the extracted config/maven-direct.xml for **both Global Settings and User Settings**. A broken global proxy can still apply if only User Settings is replaced because Maven merges both files. If your IDE does not permit changing Global Settings on that page, use the direct CLI script below and configure the IDE's Maven installation/global settings to a valid configuration. Do not edit unrelated installations blindly.
3. Apply the settings. Make sure Maven is not in Offline mode. If this network actually requires an organizational proxy, replace the direct settings with a valid proxy configuration instead.
4. Select all four projects > right-click > Maven > Update Project (Alt+F5). Enable Force Update of Snapshots/Releases and click OK.
5. Run Project > Clean. The warnings and parent-resolution markers should clear after a successful Maven refresh. If XML validation alone still fails, check whether Eclipse is allowed to download the schema URL; the build can succeed independently of that editor setting.

These Maven settings preferences affect the workspace. Record your existing settings paths first so they can be restored for projects needing an organizational repository. The project does not overwrite the machine's Maven settings files.

## Build from PowerShell

From the extracted root directory, use JDK 25 and run:

```powershell
$env:JAVA_HOME='C:\jdk25'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
./scripts/build.ps1
```

Equivalent Maven command:

```powershell
mvn -s config/maven-direct.xml -gs config/maven-direct.xml -U clean install
```

Both commands force a fresh resolution check and install the parent/library/modules into Maven's default local repository. STS must use the same local repository to reuse those artifacts. This command uses the same dependencies and WAR packaging as the original project and has no broker/database startup dependency. Oracle/Kafka integration tests remain opt-in.

If resolution still fails, expand the FIRST Maven error completely. Unknown host/proxy, TLS/certificate failure, HTTP 401/407 and artifact-not-found errors require different fixes. Do not delete your entire .m2 repository, disable TLS validation, or change Spring versions merely to clear a transfer error.

References: [Maven settings and merging](https://maven.apache.org/settings.html), [Maven project configuration](https://maven.apache.org/configure.html).
