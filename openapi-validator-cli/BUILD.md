# Building the OpenAPI Validator CLI

## Prerequisites

- Java 8 or higher (JDK, not just JRE)
- Maven 3.6 or higher
- Internet connection (to download dependencies)

## Build Commands

### Build the distribution package

```bash
cd openapi-validator-cli
mvn clean package
```

This will create:
- `target/openapi-validator-cli.jar` - The standalone executable JAR
- `target/openapi-validator-cli-1.0.0-dist.zip` - Distribution package for sharing
- `target/openapi-validator-cli-1.0.0-dist.tar.gz` - Distribution package (tar.gz)

### Quick build (skip tests)

```bash
mvn clean package -DskipTests
```

## Distribution Package Contents

After extracting the distribution archive:

```
openapi-validator-cli-1.0.0/
├── bin/
│   ├── openapi-validator       # Linux/macOS run script
│   └── openapi-validator.bat   # Windows run script
├── lib/
│   └── openapi-validator-cli.jar  # Executable JAR
├── examples/
│   ├── sample-spec.yaml        # Sample OpenAPI specification
│   ├── valid-request.json      # Example valid request
│   └── invalid-request.json    # Example invalid request
└── README.txt                  # Usage instructions
```

## Running Without Building

If you don't want to build, you can run the JAR directly after building:

```bash
java -jar target/openapi-validator-cli.jar help
```

## Distributing to Partners

1. Build the distribution package:
   ```bash
   mvn clean package
   ```

2. Share one of these files with partners:
   - `target/openapi-validator-cli-1.0.0-dist.zip` (Windows users)
   - `target/openapi-validator-cli-1.0.0-dist.tar.gz` (Linux/macOS users)

3. Partners only need Java installed - no Maven or other tools required.

## Troubleshooting Build Issues

### Maven not found
Install Maven from https://maven.apache.org/download.cgi

### Dependency download failures
- Check internet connection
- Check proxy settings in `~/.m2/settings.xml`
- Try: `mvn dependency:resolve`

### Java version issues
Ensure JAVA_HOME points to JDK 8 or higher:
```bash
echo $JAVA_HOME
java -version
```
