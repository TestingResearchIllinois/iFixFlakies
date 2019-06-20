# iFixFlakies

This plugin automatically generates patches for order-dependent tests by finding and utilizing helper test methods in the test suite.
This plugin builds upon code from iDFlakies (https://github.com/idflakies/iDFlakies), and relies on detected order-dependent tests that iDFlakies finds.

# Quickstart

After building the plugin, you can add the plugin to a Maven project by modifying the pom.xml.

```xml
<plugin>
    <groupId>edu.illinois.cs</groupId>
    <artifactId>testrunner-maven-plugin</artifactId>
    <version>1.0</version>
    <dependencies>
        <dependency>
            <groupId>edu.illinois.cs</groupId>
            <artifactId>ifixflakies</artifactId>
            <version>1.0.0-SNAPSHOT</version>
        </dependency>
    </dependencies>
    <configuration>
        <className>edu.illinois.cs.dt.tools.fixer.CleanerFixerPlugin</className>
    </configuration>
</plugin>
'''

Run the following command on the Maven project:
```
mvn testrunner:testplugin
'''
