# Building the OpenAPI Validator CLI

## Prerequisites

- Java 8 or higher (JDK, not just JRE)
- Maven 3.6 or higher
- Internet connection (to download dependencies)

## Dependencies

This CLI tool depends on the `openapi-validator` library (axway-validator module).
You must build and install the axway-validator first.

## Build Commands

### Step 1: Build and install the axway-validator library

```bash
cd axway-validator
mvn clean install
```

This installs the `be.bnppf:openapi-validator:1.0.0` artifact to your local Maven repository.

### Step 2: Build the CLI distribution package

```bash
cd openapi-validator-cli
mvn clean package
```

This will create:
- `target/openapi-validator-cli.jar` - The standalone executable JAR
- `target/openapi-validator-cli-1.0.0-dist.zip` - Distribution package for sharing
- `target/openapi-validator-cli-1.0.0-dist.tar.gz` - Distribution package (tar.gz)

### Quick build (both modules)

From the repository root:

```bash
cd axway-validator && mvn clean install && cd ../openapi-validator-cli && mvn clean package
```

### Skip tests

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
│   └── openapi-validator-cli.jar  # Executable JAR (includes all dependencies)
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

1. Build the distribution package (both steps above)

2. Share one of these files with partners:
   - `target/openapi-validator-cli-1.0.0-dist.zip` (Windows users)
   - `target/openapi-validator-cli-1.0.0-dist.tar.gz` (Linux/macOS users)

3. Partners only need Java installed - no Maven or other tools required.

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                    openapi-validator-cli                        │
│                    (be.bnppf:openapi-validator-cli)             │
│                                                                 │
│  ValidatorCLI.java  ─────────►  Uses                           │
│                                                                 │
└───────────────────────────────────┬─────────────────────────────┘
                                    │
                                    │ depends on
                                    ▼
┌─────────────────────────────────────────────────────────────────┐
│                    openapi-validator                            │
│                    (be.bnppf:openapi-validator)                 │
│                                                                 │
│  OpenAPIValidator.java                                          │
│  ValidationLevel.java                                           │
│  ValidationResult.java                                          │
│  Utils.java                                                     │
│                                                                 │
└───────────────────────────────────┬─────────────────────────────┘
                                    │
                                    │ depends on
                                    ▼
┌─────────────────────────────────────────────────────────────────┐
│            swagger-request-validator-core                       │
│            (com.atlassian.oai:swagger-request-validator-core)   │
└─────────────────────────────────────────────────────────────────┘
```

## Troubleshooting Build Issues

### "Could not resolve dependencies" for openapi-validator

Make sure you built and installed the axway-validator first:
```bash
cd axway-validator
mvn clean install
```

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

## Validation Levels

The CLI supports three validation levels (same as the library):

| Level | Description |
|-------|-------------|
| STRICT | Enforces all OpenAPI specification rules |
| LENIENT | Allows additional properties not in schema (default) |
| LIGHT | Minimal validation, most issues reported as info only |
