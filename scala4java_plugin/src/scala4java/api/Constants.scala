/**
 * Copyright (c) 2026 Florian Mantz
 * under MIT - License (see LICENSE file)
 */
package scala4java.api

/**
 * Constants used to generate file and directory names
 */
object Constants {
  val PrefixGeneratedFiles: String = "SCALA_4_MILL_"

  val FileNameGeneratedLicenceFileJar: String = PrefixGeneratedFiles + "LICENSE_JAR.txt"
  val FileNameGeneratedLicenceFileAssembly: String = PrefixGeneratedFiles + "LICENSE_ASSEMBLY.txt"

  val FolderBaseCodeUnfiltered: String = "basecode_unfiltered"
  val FolderBuildCode: String = "basecode"

  val FileNameProGuardRules: String = PrefixGeneratedFiles + "PROGUARD_RULES.pro"
  val FileNameProGuardOut: String = PrefixGeneratedFiles + "PROGUARD_OUTPUT.jar"
  val FileNameProGuardLogFile: String = PrefixGeneratedFiles + "PROGUARD.log"
}
