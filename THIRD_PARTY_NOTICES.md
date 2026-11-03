# Third-party notices

Smart File Organizer's own source code is licensed under MIT. The following components retain their upstream copyrights and license terms.

## Application dependencies

| Component | Version | Upstream | License |
| --- | --- | --- | --- |
| FlatLaf | 3.6 | FormDev Software GmbH and contributors | Apache License 2.0 |
| Gson | 2.13.1 | Google and contributors | Apache License 2.0 |
| Error Prone annotations | 2.38.0 | Google and contributors | Apache License 2.0 |

Error Prone annotations are brought in by Gson's Maven dependency graph and included in the shaded JAR.

The Apache License 2.0 text is included in [licenses/Apache-2.0.txt](licenses/Apache-2.0.txt). The built JAR contains this file under `META-INF/smart-file-organizer/licenses/`, together with this notice and the project's MIT license. FlatLaf's original embedded license is also retained under `META-INF/LICENSE`.

Upstream source and license references:

- [FlatLaf 3.6](https://github.com/JFormDesigner/FlatLaf/tree/3.6)
- [Gson 2.13.1 license](https://github.com/google/gson/blob/gson-parent-2.13.1/LICENSE)
- [Error Prone 2.38.0 license](https://github.com/google/error-prone/blob/v2.38.0/COPYING)

## Bundled Java runtime

The Windows installer built in this workspace bundles Eclipse Temurin 21.0.13+9, produced from OpenJDK. Its primary license is GPL version 2 with the Classpath Exception. Runtime components also include additional notices and licenses.

The bundled runtime retains its `runtime/legal/` directory, including per-module license files, the Classpath Exception, and third-party notices. Consult those files for the runtime's complete terms.

The runtime's upstream source repository is [adoptium/jdk21u](https://github.com/adoptium/jdk21u). The runtime release metadata identifies source revision `2ab10ca20c19` and build-source revision `1cf43d7a3e77886bfea7d550a902e151a87d724d`.

[Temurin 21 binary and source releases](https://github.com/adoptium/temurin21-binaries/releases) provide the corresponding distribution downloads. Future builds may use a newer Java 21 update. Check that build's runtime `release` metadata and retained `legal/` files.

## Development tools

JUnit 5 is used for tests and is excluded from the application JAR. Maven, WiX, and the full JDK are build tools. Their original distributions and license terms apply to those tools.

- [JUnit 5](https://github.com/junit-team/junit5)
- [Apache Maven](https://maven.apache.org/)
- [WiX Toolset](https://github.com/wixtoolset/wix3)
