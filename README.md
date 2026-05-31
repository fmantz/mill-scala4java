# mill-scala4java

## How flexible is the mill build tool? 

**tl;dr: I implemented an experimental Mill plugin that publishes a Scala library as a pure Java library.**

This experimental plugin emerged from my efforts to learn the Mill build tool. I decided to explore a use case I found particularly useful:

In the past, before Scala 3 introduced the TASTy format, getting different Scala versions to work together wasn't always easy or possible. 
In addition, while calling Java functions from Scala has always been intuitive, the reverse (calling Scala from Java) has not always been straightforward. 
Personally, I think a pure Java library is still the way to go if you want to reach both Java and Scala developers. 
I asked myself: is it possible to build a pure Java library of a decent size while still using my favorite programming language, Scala? 
My idea was to create a Java facade for a Scala library, bundle all necessary Scala dependencies within the Java JAR, shrink the output, 
and shade the classes to avoid name clashes. It sounds like a bold experiment, but I gave it a try and implemented a Mill plugin to support this specific use case.

A "hello-world" example project you find here:
https://github.com/fmantz/mill-scala4java-example/tree/main/mill-create-lib

My goal was to automate the core tasks like shrinking, shading, and POM/Ivy adjustments. Users should only need to override methods in exceptional cases. 
I aimed for a setup that is as simple as a standard Scala project:

```scala
//| mill-version: 1.1.6
//| mill-jvm-version: temurin:21
//| mvnDeps:
//| - io.github.fmantz::scala4java_plugin::0.1.0
package build

import mill._, scalalib._, publish._

object `package` extends scala4java.ScalaModule, scala4java.PublishModule {
  def artifactName = "example_lib"
  def scalaVersion = "2.11.12"
  def proguardVersion = "7.9.1"

  def mvnDeps = Seq(
    mvn"com.lihaoyi::scalatags:0.12.0",           // a scala lib
    mvn"org.jsoup:jsoup:1.22.1",                  // a java lib
    mvn"org.apache.commons:commons-lang3:3.20.0", // a java lib
    mvn"org.clapper::grizzled-slf4j:1.3.4"        // scala lib that uses a java lib (slf4j)
  )

  object test extends ScalaTests, TestModule.Utest, scala4java.TestModule(this) {
    def utestVersion = "0.8.2"
  }

  object testjava extends JavaTests, TestModule.Junit5, scala4java.TestModule(this) {
    def jupiterVersion = "5.14.0"
  }

  def publishVersion = "0.1.0"

  def pomSettings = PomSettings(
    description = "MyTest",
    organization = "xyz.test",
    url = "https://github.com/johndoe/test",
    licenses = Seq(License.MIT),
    versionControl = VersionControl.github("xyz", "mytest"),
    developers = Seq(Developer("mm", "John Doe", "https://github.com/johndoe"))
  )

}
```

Key differences include specifying a [ProGuard®](https://www.guardsquare.com/proguard) version, using a custom module instead of the standard ScalaModule/PublishModule, and adding a dedicated test module. 
As usual, the Java library can be built and published locally with the following commands:

```bash
# For local ivy repo: 
mill publishLocal

# For local maven repo:
mill publishM2Local    
```

#### How does it work (Simplified)?:

- **Compilation & Documentation**: First, the code is compiled as usual, and the Javadoc is generated for the Java facade classes.
- **Shaded Assembly**: The Mill assembly plugin builds a "pre-assembly" JAR, shading all Scala libraries (unless disabled). This assembly also includes the test code.
- **Optimization** (ProGuard): Next, ProGuard® is used (unless disabled) to shrink and/or obfuscate the assembly. We leverage the Mill ProGuard® plugin for this. 
  To avoid manual configuration, we set all local source classes (including tests) as entry points.
  Keep rules are automatically generated for all user-defined classes.
- **Verification**: After shrinking, tests are executed against the optimized assembly to ensure everything still functions correctly.
- **Module Creation**: The final JAR module is then built from a subset of these classes. It includes all Scala libraries but excludes Java libraries. 
  A third-party license file is added to provide information on the bundled libraries (unless disabled).
- **Dependency Management**: Finally, the Ivy and POM files are adjusted: all Scala libraries are removed as dependencies, while the Java dependencies from those removed Scala libs are explicitly added.

To verify that the generated JAR works correctly and can be resolved by build tools, you can test it here:
- Use the lib via mill (Java): https://github.com/fmantz/mill-scala4java-example/tree/main/use-lib/mill

```bash
mill run 
```

- Use the lib via sbt (Scala): https://github.com/fmantz/mill-scala4java-example/tree/main/use-lib/sbt

```bash
sbt run
```

- Use the lib via maven (Java): https://github.com/fmantz/mill-scala4java-example/tree/main/use-lib/maven

```bash
mvn compile exec:java
```

### The aftermath

The plugins default settings are:

```scala
def enableShadingScalaFiles = true
def enableShrinkingInBuild = true
def enableObfuscationInBuild = true
def enableLicensesInfo = true
```

With default settings and no verification tests enabled (i.e., omitting ```extends scala4java.TestModule(this)```), 
my Scala library was only **571 KB**. Disabling 'LicensesInfo' generation reduced the size further to **505 KB**. 
Adding JUnit tests barely affected the JAR size (increasing it to just 586 KB) since JUnit is Java-based and does not rely on Scala. 
However, switching to 'uTest' increased the size to 793 KB because the testing library requires additional Scala standard functions. 
Finally, using 'ScalaTest' caused a massive jump to 3.2 MB, as it heavily utilizes the Scala API.

- no tests: 571 KB
- only junit: 586 KB
- only utest: 793 KB
- only ScalaTest: 3.2 MB

- no tests + no licence info : **505 KB**!

These numbers are highly dependent on your specific setup and can vary based on several factors, 
including the libraries used, the Scala version, and your individual coding style. 
Nevertheless, I consider this experiment a success: the JAR size was significantly reduced here (compared to the sum of all needed libraries), 
and the resulting JAR remains highly versatile, working seamlessly across different setups and Scala versions.

To provide an overview of what is included in the JARs and how different settings affect the results, I have compiled some additional data:

note: ```scala4java.TestModule``` was only enabled for JUnit

- project classes (compiled. without tests):
  - 83.5K

- included Scala libraries in lib jar (total ca. 6.5 MB):
  - geny_2.11-1.0.0.jar: 105 KB
  - grizzled-slf4j_2.11-1.3.4.jar: 7.4 KB
  - scala-library-2.11.12.jar: 5.5 MB
  - scalatags_2.11-0.12.0.jar: 691 KB
  - sourcecode_2.11-0.3.0.jar 125: KB

- additionally included project classes in assembly jar (for testing):
  - 10.5 KB 

- additionally included Java libraries in assembly jar (referenced by publish jar, total ca. 1.2 MB):
  - commons-lang3-3.20.0.jar: 698 KB
  - jsoup-1.22.1.jar: 497 KB
  - slf4j-api-1.7.9.jar: 32 KB
  
- additionally included Java libraries in assembly jar (for testing, total ca. 1.3 MB):
  - apiguardian-api-1.1.2.jar: 6,7 KB
  - junit-jupiter-api-5.14.0.jar: 237 KB
  - junit-jupiter-engine-5.14.0.jar: 334 KB
  - junit-platform-commons-1.14.0.jar: 161 KB
  - junit-platform-engine-1.14.0.jar: 266 KB
  - junit-platform-launcher-1.14.0.jar: 218 KB
  - jupiter-interface-0.13.3.jar: 69 KB
  - opentest4j-1.3.0.jar: 14 KB
  - test-interface-1.0.jar: 15 KB

#### How large are my jars with different settings?

SA = shade, SI = shrink, O = Obfuscate

| Setting   | assembly.jar | publish.jar |
|-----------|--------------|-------------|
| all false | 7.8 MB       | 6.5 MB      |  
| SA        | 8.4 MB       | 7.1 MB      | 
| SI        | 2.3 MB       | 710 KB      | 
| O         | 8.0 MB       | unsupported | 
| SA,SI     | 2.8 MB       | 792 KB      | 
| SA,O      | 7.9 MB       | 5.5 MB      |
| SI,O      | 8.0 MB       | unsupported |
| SI,SA,O   | 2.8 MB       | 586 KB      | 

note: In the current implementation, obfuscation requires shading for the publish.jar. 
Furthermore, please note that both shading and shrinking are required to make the JAR compatible with any Scala or Java project.

### Conclusion

Mill is remarkably easy to customize, even for niche use cases like the one described above. 
Whether publishing Scala libraries as pure Java libraries is advisable remains a different matter. 
Furthermore, the final JAR size depends heavily on the Scala features used and the effectiveness of the ProGuard® optimization, 
making size predictions difficult. I'm not entirely convinced yet, but it was a great exercise. 
Feel free to share your thoughts in the discussion!

**Huge thanks to all the engineers and maintainers behind the tools powering this plugin, especially Mill, ProGuard, and Apache BCEL!**

### DISCLAIMER:

This plugin has only been tested on two small personal projects; therefore, it should be considered **experimental and not production-ready**. 
Feel free to try it out on your hobby projects, **but please do not publish any resulting artifacts to Maven Central**, I doubt they would appreciate it!
**I accept no liability or responsibility for any legal issues that may arise from using this software.**

Furthermore, please note one limitation: all test code and resources flagged by scala4java.TestModule(this) will be included in the preassembly. 
To avoid potential name clashes, ensure you use unique names across different test modules.

