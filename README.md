# japicc-plugin
[![Build Status](https://travis-ci.com/efenglu/japicc-plugin.svg?branch=master)](https://travis-ci.com/efenglu/japicc-plugin)

A Maven plugin that enforces Java Semantic Versioning

Usage:
```xml
<plugin>
    <groupId>io.github.efenglu.japicc</groupId>
    <artifactId>japicc-plugin</artifactId>
    <version>1.0.1</version>
    <executions>
        <execution>
            <goals>
                <goal>check</goal>
            </goals>
            <phase>verify</phase>
        </execution>
    </executions>
</plugin>
```
