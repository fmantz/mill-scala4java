# mill-scala4java

## How flexible is the Mill build tool? 

**tl;dr: I implemented an experimental Mill plugin that publishes a Scala library as a pure Java library.**

This experimental plugin grew out of my efforts to learn the Mill build tool. I decided to tackle a use case I found challenging:

In the past, before Scala 3 introduced the TASTy format, getting different Scala versions to work together wasn't always easy or possible. 
In addition, while calling Java functions from Scala has always been intuitive, the reverse (calling Scala from Java) has not always been straightforward. 
Personally, I think a pure Java library is still the way to go if you want to reach both Java and Scala developers. 
I asked myself: is it possible to build a pure Java library of a decent size while still using my favorite programming language, Scala? 
My idea was to create a Java facade for a Scala library, bundle all necessary Scala dependencies within the Java JAR, shrink the output, 
and shade the third-party Scala classes to avoid name clashes. It sounds like a bold experiment, but I gave it a try and implemented a Mill plugin to support this specific use case.
For me, this was a case study to see how customizable the Mill build tool is.

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
./mill publishLocal

# For local maven repo:
./mill publishM2Local    
```

#### How does it work (Simplified):

1. **Compilation & Documentation**: First, the code is compiled as usual, and the Javadoc is generated for the Java facade classes.
2. **Shaded Assembly**: Then, the Mill assembly plugin builds a "pre-assembly" JAR, shading all Scala libraries unless disabled. 
  This pre-assembly also includes test code and libraries, which are excluded from the later "publish" JAR (i.e. `mill jar`). 
  The file is called a "pre-assembly" because it serves as the foundation for both the "publish" and final "assembly" JARs (i.e. `mill assembly`).
  The testing code is added to the pre-assembly for the later verification step. The testing code needs to be added at this stage 
  because the Scala standard library is also shaded. Consequently, the test code can no longer be called on the "pre-assembly" JAR 
  unless the testing code and libraries are adjusted accordingly.
3. **Optimization** (ProGuard®): Next, ProGuard® is used (unless disabled) to shrink and/or obfuscate the pre-assembly. We leverage the Mill ProGuard® plugin for this.
  To avoid manual configuration, we set all local source classes—including tests—as entry points. ProGuard® keep rules are automatically generated for all user-defined classes. 
  However, this step may break the code, as perfect auto-generation of ProGuard® rules is not possible.
4. **Verification**: After shrinking, tests are executed against the optimized pre-assembly to ensure everything still functions correctly.
5. **Module Creation**: The final JAR module is then built from a subset (!) of these pre-assembly classes. 
  It includes all Scala libraries but excludes Java libraries, testing libraries and testing code.
  A third-party license file is added to provide information on the bundled libraries (unless disabled).
6. **Dependency Management**: Finally, the Ivy and POM files are adjusted: all Scala libraries are removed as dependencies, while the Java dependencies from those removed Scala libs are explicitly added.

To verify that the generated JAR works correctly and can be resolved by build tools, you can test it here:
- Use the lib via mill (Java): https://github.com/fmantz/mill-scala4java-example/tree/main/use-lib/mill

```bash
./mill run 
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

With default settings and no verification tests enabled (i.e., omitting `extends scala4java.TestModule(this)`), 
the Java library of the example project above was only **571 KB**. Disabling 'LicensesInfo' generation reduced the size further to **505 KB**. 
Adding JUnit tests barely affected the JAR size (increasing it to just 586 KB) since JUnit is Java-based and does not rely on Scala. 
However, switching to 'uTest' increased the size to 793 KB because the testing library requires additional Scala standard functions 
(code from the scala-library.jar). Finally, using 'ScalaTest' caused a massive jump to 3.2 MB, as it heavily utilizes the Scala API.

- no tests: 571 KB
- only junit: 586 KB
- only utest: 793 KB
- only ScalaTest: 3.2 MB

- no tests + no licence info : **505 KB**!

These numbers are highly dependent on your specific project and setup and can vary based on several factors, 
including the libraries used, the Scala version, and your individual coding style. 
Nevertheless, I consider this experiment a success: the JAR size of the example project was significantly reduced here (compared to the sum of all needed libraries), 
and the resulting JAR remains highly versatile, working seamlessly across different setups and Scala versions.

To provide an overview of what is included in the example JAR and how different settings affect the results, I have compiled some additional data:

note: `scala4java.TestModule` was only enabled for JUnit

- project classes (compiled. without tests):
  - 83.5K

- included Scala libraries in publish.jar (total ca. 6.5 MB):
  - geny_2.11-1.0.0.jar: 105 KB
  - grizzled-slf4j_2.11-1.3.4.jar: 7.4 KB
  - scala-library-2.11.12.jar: 5.5 MB
  - scalatags_2.11-0.12.0.jar: 691 KB
  - sourcecode_2.11-0.3.0.jar 125: KB

#### How large are my jars with different settings?

SA = shade, SI = shrink, O = Obfuscate

| Setting   | publish.jar |
|-----------|-------------|
| all false | 6.5 MB      |  
| SA        | 7.1 MB      | 
| SI        | 710 KB      | 
| O         | unsupported | 
| SA,SI     | 792 KB      | 
| SA,O      | 5.5 MB      |
| SI,O      | unsupported |
| SI,SA,O   | 586 KB      | 

note: In the current implementation, obfuscation requires shading for the publish.jar. 
Furthermore, please note that both shading and shrinking are required to make the JAR compatible with any Scala or Java version.


### Limitations

In the **scala4java** plugin implementation, Mill's assembly feature is used to create the base for JAR files. 
Consequently, the `resolvedIvyAssembly` and `upstreamAssembly` methods have been overridden. 
Ultimately, this plugin focuses primarily on "JAR" functionality (i.e. `mill jar`, `mill publishLocal`, `mill publishM2Local`) 
and treats the assembly (`mill assembly`) merely as an underlying tool.

Therefore, note a few minor limitations:

- All test code and resources flagged by `scala4java.TestModule(this)` will be included in the (pre-)assembly.
  To avoid potential naming clashes, ensure that you use unique names across different test modules.
- Additionally, some unnecessary test classes and test library JARs may be included in the assembly.
  While this can be fixed, it is currently considered a low priority.
- Using the ProGuard®-optimized code instead of the original code also in the assembly was a design decision. 
  Basically, the plugin reuses the pre-assembly JAR as the assembly JAR, only prepending the usual Mill starter scripts. 

### Conclusion

Mill is remarkably easy to customize, even for niche use cases like the one described above. 
Whether publishing Scala libraries as pure Java libraries is advisable remains a different matter. 
Furthermore, the final JAR size depends heavily on the Scala features used and the effectiveness of the ProGuard® optimization, 
making size predictions difficult. I'm not entirely convinced of the approach yet, but it was a great exercise.
If JAR size is a priority, it is advisable to use either a Java testing framework or a lightweight Scala testing framework for the verification step.
This allows ProGuard® to better optimize the Scala standard library classes. Feel free to share your thoughts in the discussion!

**Huge thanks to all the engineers and maintainers behind the tools powering this plugin, especially Scala, Mill, ProGuard®, and Apache BCEL!**

### DISCLAIMER:

This plugin has only been tested on two small personal projects (on Linux); therefore, it should be considered **experimental and not production-ready**. 
Feel free to try it out on your hobby projects, **but please do not publish any resulting artifacts to Maven Central**, I doubt they would appreciate it!
**I accept no liability or responsibility for any legal issues that may arise from using this software.**

### Note

I separated the example code from the plugin code.

To try the example, just clone the GitHub repository (the plugin code is available on Maven Central):

```bash
git clone https://github.com/fmantz/mill-scala4java-example.git
```

To compile the plugin yourself, clone the GitHub repository:

```bash
git clone https://github.com/fmantz/mill-scala4java.git
```

**Enjoy!**

### Troubleshooting

Even the official Mill example from https://github.com/scala/scala3-mill-example-project is not a library you would want to reuse; 
I tested the plugin with it. Unfortunately, the autogenerated ProGuard® rules were not sufficient. I needed to add two extra rules. 
Therefore, I disabled obfuscation first to better understand where the problem was. 
Then, I extended the autogenerated ProGuard® entry points as follows:

```scala
//| mill-version: 1.1.6
//| mill-jvm-version: temurin:21
//| mvnDeps:
//| - io.github.fmantz::scala4java_plugin::0.1.0
import mill._, scalalib._

object examples extends scala4java.ScalaModule {
  def scalaVersion = "3.8.4"
  def proguardVersion = "7.9.1"

  // its easier to work with real names (even the jar will be some bytes larger):
  def enableObfuscationInBuild = false

  // add some extra Progard entry rules:
  def prepareAssemblyProGuardEntryPoints = Task {
    val prefix = shadedRootDir() + "." + shadedSourceCodeId()
    super.prepareAssemblyProGuardEntryPoints() ++
      Seq(
        s"-keep class $prefix.scala.math.BigDecimal { *; }",
        s"-keep class $prefix.scala.concurrent.** { *; }"
      )
  }
}
```

You can test it here:

```bash
git clone https://github.com/fmantz/scala3-mill-example-project

./mill examples.assembly

java -jar ./out/examples/assembly.dest/out.jar
```

The assembly JAR size dropped from **9 MB** to **1.5 MB**.
Since the scala4java plugin allows you to override any default setting, 
you should be able to resolve any issues that may arise.

Usually, adding a missing ProGuard® rule solves the issue. 
Alternatively, you can disable ProGuard® entirely: 

```scala
//| mill-version: 1.1.6
//| mill-jvm-version: temurin:21
//| mvnDeps:
//| - io.github.fmantz::scala4java_plugin::0.1.0
import mill._, scalalib._

object examples extends scala4java.ScalaModule {
  def scalaVersion = "3.8.4"
  def enableShrinkingInBuild = false
  def enableObfuscationInBuild = false
}
```
