# Architecture Diagrams

These diagrams can be rendered using [Mermaid Live Editor](https://mermaid.live) or any Mermaid-compatible viewer.

---

## 1. High-Level Architecture

```mermaid
flowchart LR
    subgraph Client
        A[Client Application]
    end

    subgraph Gateway["Axway API Gateway"]
        B[Request Received]
        C[Groovy Script Filter]
        D[OpenAPI Validator JAR]
        E{Valid?}
    end

    subgraph Backend
        F[Backend API]
    end

    A -->|HTTP Request| B
    B --> C
    C --> D
    D --> E
    E -->|Yes| F
    E -->|No| G[400 Bad Request]
    G --> A
    F -->|Response| A
```

---

## 2. Component Diagram

```mermaid
classDiagram
    class OpenAPIValidator {
        -validator: OpenApiInteractionValidator
        -validationLevel: ValidationLevel
        +getInstance(spec, level, cache)
        +isValidRequest(method, path, headers, params, body)
        -buildValidator(builder, level)
    }

    class ValidationLevel {
        <<enumeration>>
        LIGHT
        LENIENT
        STRICT
        +fromString(level)
    }

    class ValidationResult {
        -errors: List
        -blocked: boolean
        +isValid()
        +isBlocked()
        +getErrors()
        +addError(error)
    }

    class Utils {
        +traceMessage(message, level)
        +extractHeaders(headerSet)
        +generateCacheKey(spec, level)
    }

    OpenAPIValidator --> ValidationLevel : uses
    OpenAPIValidator --> ValidationResult : returns
    OpenAPIValidator --> Utils : uses
```

---

## 3. Request Validation Flow

```mermaid
sequenceDiagram
    participant Client
    participant Gateway as Axway Gateway
    participant Script as Groovy Script
    participant Validator as OpenAPIValidator
    participant Backend

    Client->>Gateway: HTTP Request
    Gateway->>Script: Execute Filter

    Script->>Script: Extract request details
    Script->>Script: Load OpenAPI spec

    Script->>Validator: getInstance(spec, level, cache)
    Validator->>Validator: Check cache

    alt Cache Hit
        Validator-->>Script: Return cached validator
    else Cache Miss
        Validator->>Validator: Parse spec
        Validator->>Validator: Store in cache
        Validator-->>Script: Return new validator
    end

    Script->>Validator: isValidRequest(method, path, headers, params, body)
    Validator->>Validator: Validate against spec
    Validator-->>Script: ValidationResult

    alt Valid Request
        Script-->>Gateway: return true
        Gateway->>Backend: Forward request
        Backend-->>Client: Response
    else Invalid Request
        Script-->>Gateway: return false
        Gateway-->>Client: 400 Bad Request
    end
```

---

## 4. Caching Mechanism

```mermaid
flowchart TB
    A[Request with OpenAPI Spec] --> B{Calculate Cache Key}
    B --> C[MD5 Hash of Spec + Level]
    C --> D{Key in Cache?}

    D -->|Yes| E[Return Cached Validator]
    D -->|No| F[Parse OpenAPI Spec]

    F --> G[Create Validator Instance]
    G --> H[Store in ConcurrentHashMap]
    H --> I[Return New Validator]

    E --> J[Validate Request]
    I --> J
```

---

## 5. Validation Level Decision Tree

```mermaid
flowchart TD
    A[Start] --> B{New API?}
    B -->|Yes| C[STRICT]
    B -->|No| D{Migrating from legacy?}
    D -->|Yes| E[LIGHT]
    D -->|No| F{High security required?}
    F -->|Yes| C
    F -->|No| G[LENIENT]

    C --> H[Most restrictive]
    E --> I[Most permissive]
    G --> J[Balanced - Recommended]
```

---

## 6. Validation Levels Comparison

```mermaid
graph LR
    subgraph LIGHT
        L1[Basic path check]
        L2[Method validation]
        L3[Warnings only]
    end

    subgraph LENIENT
        M1[Path check]
        M2[Method validation]
        M3[Required fields]
        M4[Type checking]
        M5[Extra props OK]
    end

    subgraph STRICT
        S1[Path check]
        S2[Method validation]
        S3[Required fields]
        S4[Type checking]
        S5[No extra props]
        S6[All constraints]
    end

    LIGHT -.->|More strict| LENIENT
    LENIENT -.->|More strict| STRICT
```

---

## 7. Error Handling Flow

```mermaid
flowchart TB
    A[Validation Result] --> B{Has Errors?}
    B -->|No| C[Request Valid]
    B -->|Yes| D{Check Error Severity}

    D --> E{LIGHT Level}
    D --> F{LENIENT Level}
    D --> G{STRICT Level}

    E --> H{Critical Error?}
    H -->|Yes| I[Block Request]
    H -->|No| J[Log Warning, Allow]

    F --> K{Missing Required or Type Error?}
    K -->|Yes| I
    K -->|No| J

    G --> L{Any Error?}
    L -->|Yes| I
    L -->|No| C

    C --> M[Continue to Backend]
    I --> N[Return 400 Bad Request]
```

---

## 8. Deployment Architecture

```mermaid
flowchart TB
    subgraph Build["Build Environment"]
        A[Source Code] --> B[Maven Build]
        B --> C[openapi-validator-1.0.0.jar]
    end

    subgraph Deploy["Deployment"]
        C --> D[Copy to ext/lib]
        E[Groovy Script] --> F[Policy Studio]
    end

    subgraph Runtime["Axway Runtime"]
        D --> G[JAR Loaded]
        F --> H[Policy Deployed]
        G --> I[Validator Available]
        H --> I
    end
```

---

## How to Export These Diagrams

### Option 1: Mermaid Live Editor
1. Go to [mermaid.live](https://mermaid.live)
2. Copy the Mermaid code (between ```mermaid and ```)
3. Paste in the editor
4. Download as PNG or SVG

### Option 2: VS Code Extension
1. Install "Mermaid Preview" extension
2. Open this file
3. Use preview to see rendered diagrams
4. Export as needed

### Option 3: GitHub
GitHub automatically renders Mermaid diagrams in markdown files.

---

## Diagram Export Checklist

- [ ] High-Level Architecture → `architecture-overview.png`
- [ ] Component Diagram → `component-diagram.png`
- [ ] Request Validation Flow → `validation-flow.png`
- [ ] Caching Mechanism → `caching-mechanism.png`
- [ ] Validation Level Decision Tree → `level-decision.png`
- [ ] Validation Levels Comparison → `levels-comparison.png`
- [ ] Error Handling Flow → `error-handling.png`
- [ ] Deployment Architecture → `deployment.png`
