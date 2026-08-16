# SRS — Tributary

**Versión:** 1.2
**Fecha:** 2026-08-14
**Autor:** Luis Alejandro Cerón Muñoz | **Revisor técnico:** Arch-Sentinel
**Estado:** **Aprobado** — sin `[PENDIENTE]` abiertos

---

## 0. Registro de resolución de pendientes

Ningún `[PENDIENTE]` queda abierto. Este SRS es la fuente de verdad del sistema; cualquier cambio posterior se hace por revisión versionada en §12, nunca por decisión implícita durante la implementación.

| ID | Pendiente | Resolución | Consecuencia en el diseño |
|----|-----------|-----------|---------------------------|
| P-01 | Credenciales de sandbox de Factus | **Disponibles.** | La fase 3 del Build Order se ejecuta en su día sin desacoplar. A-002 pasa a ser un activo vivo desde el primer commit: gitleaks corre en pre-commit, no solo en CI. |
| P-02 | Modelo de entrega | **Milestone 1 local** (docker-compose reproducible). Despliegue público diferido a Milestone 2 con gate de entrada. | El threat model de este SRS cubre exclusivamente el escenario local. Ver §10.5. |
| P-03 | Licencia | **Apache-2.0.** | Concesión expresa de patentes y cláusula de contribución. `LICENSE` y `NOTICE` en la raíz desde la fase 1. |
| P-04 | Validación de XRechnung | **Validador oficial KoSIT**, versión fijada y verificada por checksum. | Ver ADR-008, CV-05 y T-012. Añade una dependencia de cadena de suministro que hay que tratar como tal. |

**Nota de lectura de seguridad.** Los controles de seguridad de este sistema están distribuidos en cinco lugares: los requisitos funcionales (RF-006, RF-007), los no funcionales (§5.3), el threat model (§7), la matriz de verificación (§9A) y la gestión de riesgo (§10). Ninguna capa se sostiene sola; la §9A es el índice que prueba que la cobertura está cerrada.

---

## 1. Resumen ejecutivo

Una venta B2B transfronteriza genera obligaciones fiscales simultáneas en dos jurisdicciones con modelos técnicos incompatibles: Colombia opera bajo *clearance* (la DIAN valida y devuelve un CUFE antes de que la factura exista legalmente), España exige un registro de facturación encadenado por hash SHA-256 con inalterabilidad garantizada (RD 1007/2023), y Alemania opera bajo post-auditoría, donde la obligación es entregar al comprador un documento estructurado conforme a EN 16931.

Tributary es un backend que modela el hecho de negocio una sola vez y delega a adaptadores la traducción a cada régimen. La tesis técnica es que los tres regímenes son implementaciones de un mismo principio —un documento fiscal es un hecho inmutable, corregible solo por un documento posterior que lo referencia— y que esa invariante debe vivir en la capa que ninguna ruta de la aplicación puede esquivar.

El sistema no es un producto comercial. Es una implementación de referencia construida contra especificaciones públicas, cuyo objetivo es demostrar diseño hexagonal, integración con API externa irreversible y controles de seguridad verificables. No está certificado bajo ningún régimen y no debe usarse para facturar en producción.

---

## 2. Stakeholders y usuarios

| Rol | Necesidad principal | Criterio de éxito |
|-----|---------------------|-------------------|
| Operador de facturación (rol `OPERATOR`) | Emitir una factura por una venta transfronteriza sin conocer los detalles de cada régimen | Una llamada al caso de uso produce documentos válidos en todos los regímenes aplicables, o falla completa y explícitamente sin emitir a medias |
| Auditor / inspector (rol `AUDITOR`) | Demostrar que ningún registro fue alterado desde su emisión | El verificador de cadena recorre el histórico completo y devuelve `INTACT`, o señala el registro exacto donde se rompe |
| Titular de datos personales (comprador) | Ejercer supresión bajo GDPR art. 17 sin destruir la contabilidad del emisor | Tras la supresión, la PII no es recuperable y la cadena sigue verificando `INTACT` |
| Evaluador técnico (revisor del portafolio) | Entender la decisión de arquitectura en menos de diez minutos | README y ADRs en inglés explican el puerto `FiscalRegime` y las tres implementaciones sin necesidad de leer código |
| Autor (Luis) | Pieza de portafolio orientada a backend/arquitectura en DACH y España | Proyecto completo y verificable en ≤7 días de trabajo efectivo |

---

## 3. Alcance

**En scope:**

- Núcleo de dominio de factura modelado sobre la semántica EN 16931 (términos BT/BG), independiente de framework y de cualquier régimen.
- Puerto `FiscalRegimePort` con tres adaptadores: `CO-DIAN` (vía Factus, contra sandbox real), `ES-VERIFACTU` (registros encadenados, local), `DE-EN16931` (XRechnung XML, local).
- Emisión, anulación/rectificación y consulta de documentos.
- Cadena de integridad SHA-256 aplicada y verificada en PostgreSQL.
- Idempotencia de emisión y reconciliación ante respuesta perdida.
- Supresión de datos personales por destrucción de clave (crypto-shredding).
- API REST documentada con OpenAPI, autenticación OAuth2 *client credentials*, autorización por rol.
- CI con SAST, SCA, secrets scanning y la matriz de verificación de §9A.

**Fuera de scope (explícito):**

- Interfaz de usuario de cualquier tipo. El proyecto apunta a un lector backend; una UI mediocre resta.
- Envío real de registros a la AEAT (requiere certificado cualificado y alta como SIF). El adaptador ES genera y encadena registros, no los remite. Ver ADR-005.
- Transmisión por red Peppol (requiere Access Point acreditado). El adaptador DE genera y valida el documento; no lo transporta.
- Emisión en producción contra la DIAN. Solo sandbox de Factus.
- Nómina electrónica, documentos soporte, notas débito, tipos de operación distintos de `10` (estándar) en el régimen CO.
- Catálogo de productos, gestión de clientes, cobranza, contabilidad. Se consumen como *fixtures*.
- Multi-tenancy. Un emisor único. (Esa historia ya la cuenta CareLink; repetirla no añade señal.)
- **Despliegue público accesible desde internet.** Milestone 1 entrega un `docker-compose` reproducible en local. La exposición pública es Milestone 2 y no se ejecuta sin superar el gate de §10.5.
- Componentes agénticos o LLM. La extensión M del skill no aplica y no se evalúa OWASP LLM Top 10.

---

## 4. Requisitos funcionales

### RF-001: Registrar una venta transfronteriza como factura en borrador

- **Descripción:** el sistema acepta los datos de una venta y construye un documento de dominio válido según EN 16931, sin emitirlo todavía.
- **Actores:** `OPERATOR`.
- **Precondiciones:** existe un emisor configurado con sus identificadores fiscales por jurisdicción.
- **Flujo principal:**
  1. El operador envía cabecera, comprador, líneas e información de pago.
  2. El sistema valida las reglas de negocio EN 16931 aplicables (base imponible, coherencia de impuestos, moneda, identificadores).
  3. El sistema calcula totales con `BigDecimal` a escala 2 y redondeo `HALF_UP`.
  4. El sistema persiste el documento en estado `DRAFT` con un `businessKey` derivado determinísticamente de la venta.
- **Flujos alternativos:**
  - Datos inválidos → `422` con la lista completa de violaciones; nada se persiste.
  - `businessKey` ya existente en estado distinto de `DRAFT` → `409`; no se crea un segundo borrador.
- **Postcondiciones:** un documento en `DRAFT`, sin efecto fiscal, editable.
- **Criterios de aceptación:**
  - Dos peticiones idénticas producen exactamente un documento (verificable por conteo en DB).
  - Un total calculado por el dominio nunca difiere del recalculado por el test de propiedades en más de 0.00.
  - El dominio no importa ninguna clase de Spring, Jackson ni driver JDBC (verificable con ArchUnit).

### RF-002: Emitir bajo el régimen colombiano (modelo clearance)

- **Descripción:** el documento se traduce al payload de Factus, se envía a `POST /v2/bills/validate` y el CUFE devuelto se adopta como prueba de emisión.
- **Actores:** `OPERATOR`.
- **Precondiciones:** documento en `DRAFT`; credenciales de sandbox provistas por variable de entorno; token OAuth2 de Factus vigente.
- **Flujo principal:**
  1. Transición `DRAFT → SUBMITTING`, confirmada en transacción propia antes de cualquier E/S de red.
  2. Se resuelve el rango de numeración vigente.
  3. Se envía el payload con `reference_code` = `businessKey`.
  4. Con `201`, se persisten `number`, `cufe`, `validated_at`, `errors` y la respuesta cruda; transición a `ISSUED`.
- **Flujos alternativos:**
  - Timeout o error de red → estado `NEEDS_RECONCILIATION`. **Nunca se reintenta a ciegas.**
  - `429` → se respeta `Retry-After` con *jitter*; se contabiliza como fallo del limitador propio, no como operación normal.
  - `is_validated = false` → `REJECTED`, con el contenido de `errors` preservado íntegro.
  - `errors` no vacío con `is_validated = true` → `ISSUED_WITH_WARNINGS`; las advertencias de la DIAN se registran y no se descartan.
- **Postcondiciones:** documento `ISSUED` con CUFE, o en un estado terminal explícito. Nunca en un estado ambiguo.
- **Criterios de aceptación:**
  - Matar el proceso entre el envío y la recepción no produce un segundo documento en Factus (verificable listando por `reference_code`).
  - 20 hilos concurrentes sobre el mismo documento producen exactamente una emisión.
  - Ninguna petición saliente supera 60 req/min medidas en ventana deslizante.

### RF-003: Emitir bajo el régimen español (registro encadenado)

- **Descripción:** se genera un *registro de facturación de alta* con huella SHA-256 que incorpora la huella del registro anterior, más el QR de verificación.
- **Actores:** `OPERATOR`.
- **Precondiciones:** documento en `DRAFT`.
- **Flujo principal:**
  1. Se construye el registro de alta con los campos mínimos del RD 1007/2023.
  2. Se calcula la huella SHA-256 sobre los campos canonicalizados más la huella del registro anterior de la misma cadena.
  3. Se inserta el registro; el trigger de base de datos valida el encadenamiento antes de aceptar la fila.
  4. Se genera el QR con la estructura de campos del régimen, apuntando al endpoint de verificación **propio** del sistema, junto a la leyenda de operación en modo no remitido (ver ADR-007).
- **Flujos alternativos:**
  - Encadenamiento incoherente → la inserción se rechaza en la base de datos, no en la aplicación.
  - Inserción concurrente sobre la misma cadena → serializada por bloqueo; nunca dos registros con el mismo predecesor.
- **Postcondiciones:** registro inmutable, encadenado, con huella y QR.
- **Criterios de aceptación:**
  - Un `UPDATE` directo por cliente SQL sobre un registro encadenado es rechazado por el trigger.
  - El QR generado **no** contiene ninguna URL del dominio de la AEAT, y su leyenda declara el modo no remitido (verificable con un test que falla si aparece un host de la AEAT en el contenido del QR).
  - Alterar un registro intermedio saltándose el trigger (con el trigger deshabilitado en el test) hace que el verificador de RF-006 devuelva `BROKEN` señalando ese registro exacto.
  - La huella es reproducible: recalcularla desde los datos persistidos da el mismo valor.

### RF-004: Anular o rectificar un documento emitido

- **Descripción:** un documento emitido nunca se edita. La corrección es un documento nuevo que referencia al original, con la forma que exige cada régimen.
- **Actores:** `OPERATOR`.
- **Precondiciones:** documento en `ISSUED` o `ISSUED_WITH_WARNINGS`.
- **Flujo principal:**
  1. Se registra el motivo de la corrección.
  2. Régimen CO → se emite una nota crédito que referencia el documento original.
  3. Régimen ES → se genera un *registro de anulación* vinculado al registro de alta, encadenado igual que cualquier otro.
  4. El original conserva su estado; se añade la referencia bidireccional.
- **Flujos alternativos:**
  - Intento de anular un documento ya anulado → `409`.
  - Intento de `UPDATE` sobre el original por cualquier vía → rechazado por trigger.
- **Postcondiciones:** dos documentos vinculados; ninguno modificado.
- **Criterios de aceptación:**
  - No existe ninguna ruta en la API que permita modificar un documento emitido (verificable por revisión de contratos y test negativo por endpoint).
  - Tras la anulación, la cadena de registros ES sigue verificando `INTACT`.

### RF-005: Generar la entrega estructurada para el régimen alemán

- **Descripción:** el documento se serializa a XRechnung (sintaxis CII, perfil EN 16931) y se valida contra el esquema y las reglas de negocio.
- **Actores:** `OPERATOR`.
- **Precondiciones:** documento en `DRAFT` o `ISSUED`.
- **Flujo principal:**
  1. Mapeo del modelo de dominio a los términos BT/BG de EN 16931.
  2. Serialización a XML.
  3. Validación con el validador oficial KoSIT (XSD más escenarios Schematron), con versión fijada y checksum verificado (ADR-008).
  4. Entrega del artefacto al llamante.
- **Flujos alternativos:**
  - Violación de una regla de negocio EN 16931 → `422` con el identificador de la regla (`BR-xx`), nunca un XML inválido silencioso.
- **Postcondiciones:** artefacto XML válido y trazable al documento de dominio.
- **Criterios de aceptación:**
  - El XML generado pasa la validación sin advertencias para los tres casos de prueba definidos.
  - Un documento que viola `BR-CO-10` (suma de bases) es rechazado antes de serializar.

### RF-006: Verificar la integridad de la cadena de registros

- **Descripción:** recorrer la cadena completa recalculando cada huella y comparándola con la persistida.
- **Actores:** `AUDITOR`, y un job programado.
- **Precondiciones:** existe al menos un registro.
- **Flujo principal:** recorrido ordenado, recálculo, comparación, reporte.
- **Flujos alternativos:** ante la primera discrepancia, se reporta el registro, su predecesor y ambas huellas. El recorrido continúa para cuantificar el alcance.
- **Postcondiciones:** veredicto `INTACT` o `BROKEN` con el detalle.
- **Criterios de aceptación:**
  - Sobre una cadena sana de 1.000 registros, devuelve `INTACT` en < 2 s.
  - Sobre una cadena con un registro alterado, identifica exactamente ese registro (test determinista, no probabilístico).
  - El verificador no tiene permisos de escritura sobre las tablas que audita (usuario de DB distinto).

### RF-007: Ejercer la supresión de datos personales sin romper la cadena

- **Descripción:** la PII del comprador se cifra con AES-256-GCM bajo una clave por titular; la supresión destruye la clave.
- **Actores:** `ADMIN`.
- **Precondiciones:** solicitud registrada con su base legal.
- **Flujo principal:**
  1. Se verifica que no exista una obligación de conservación activa que impida la supresión de los campos solicitados.
  2. Se destruye la clave del titular.
  3. Se registra el evento en la bitácora append-only: quién, cuándo, sobre qué titular, con qué justificación. **Sin incluir la PII suprimida.**
- **Flujos alternativos:**
  - Clave ya destruida → operación idempotente, `200` con estado ya alcanzado.
- **Postcondiciones:** PII no recuperable; huellas y cadena intactas; registro fiscal auditable en sus campos no personales.
- **Criterios de aceptación:**
  - Tras la supresión, el texto claro no aparece en ningún volcado de la base de datos (verificable con `grep` sobre el `pg_dump`).
  - RF-006 devuelve `INTACT` después de la supresión.
  - La operación exige rol `ADMIN` y queda registrada; un `OPERATOR` recibe `403`.

### RF-008: Consultar y reconciliar el estado de emisión

- **Descripción:** ante un documento en `NEEDS_RECONCILIATION`, el sistema consulta al régimen externo antes de decidir.
- **Actores:** job de reconciliación; `AUDITOR` en modo lectura.
- **Flujo principal:** consulta por `reference_code` → si existe y está validado, se adopta el CUFE; si no existe, se reintenta la emisión; si la respuesta es ambigua, se detiene y se alerta.
- **Flujos alternativos:** tres reconciliaciones ambiguas consecutivas → estado `MANUAL_REVIEW`, sin más automatismo.
- **Criterios de aceptación:**
  - Ninguna ruta del reconciliador puede emitir sin haber consultado primero (verificable por test con el cliente externo mockeado y aserción de orden de llamadas).
  - El estado `MANUAL_REVIEW` no tiene transición automática de salida.

---

## 5. Requisitos no funcionales

### 5.1 Rendimiento

- Latencia p95 < 150 ms para RF-001 (operación puramente local).
- Latencia p95 del caso de uso completo de RF-002 dominada por la API externa; el presupuesto propio del sistema (todo menos la llamada saliente) es < 200 ms.
- Verificación de cadena (RF-006): < 2 s para 1.000 registros, < 20 s para 100.000.
- Throughput objetivo: 20 emisiones/minuto sostenidas, limitado deliberadamente por debajo del techo de 80 req/min de Factus.

### 5.2 Disponibilidad

- SLA objetivo: **no aplica** — implementación de referencia entregada como `docker-compose` local, sin compromiso de servicio. Se declara explícitamente en vez de omitirse.
- Reproducibilidad como sustituto de disponibilidad: `docker compose up` levanta el sistema completo con datos de ejemplo en menos de 3 minutos desde un clon limpio. Ese es el criterio verificable que reemplaza al SLA en Milestone 1.
- RTO: 4 h (redeploy desde el repositorio y restauración del volumen).
- RPO: 0 para documentos emitidos. Una emisión confirmada por el régimen externo y no persistida localmente es el peor fallo posible del sistema; por eso la transición de estado se confirma antes de la E/S de red y la reconciliación es obligatoria.

### 5.3 Seguridad

**Autenticación.** OAuth2 *client credentials* contra el propio servidor de autorización del sistema. JWT firmado con RS256 — nunca `none`, nunca HS256. Access token de 15 min, sin refresh (flujo máquina a máquina). Claves en el vault de despliegue, jamás en el repositorio.

**Autorización.** RBAC con tres roles y separación de funciones: `OPERATOR` emite y anula pero no suprime ni administra claves; `AUDITOR` solo lee y verifica; `ADMIN` gestiona claves y supresiones pero no emite. Ningún rol acumula emisión y destrucción de evidencia.

**Datos sensibles identificados.** Identificadores fiscales del comprador (NIT/DV, NIF, USt-IdNr), razón social, dirección, correo, teléfono; el `client_secret` de Factus; las claves de cifrado por titular; el material de firma JWT.

**Compliance de referencia.** GDPR (Reglamento UE 2016/679, arts. 5, 17, 32), Ley 1581 de 2012 y Decreto 1074 de 2015 (Colombia), RD 1007/2023 y Orden HAC/1177/2024 (España), EN 16931 y sus reglas de negocio. **El sistema no está certificado bajo ninguno de estos regímenes**; los implementa como especificación técnica.

**Cabeceras HTTP como input no confiable.** `User-Agent`, `Referer`, `X-Forwarded-For`, `X-Real-IP`, `Host`, `Cookie`, `Accept-Language` y cualquier cabecera personalizada se tratan como entrada hostil sin excepción:

- Jamás se concatenan en SQL, comandos, rutas de archivo ni plantillas.
- **Toda** escritura a base de datos que las incluya —incluidas las de logging y auditoría— usa sentencias preparadas. Una tabla de auditoría escrita por concatenación es un sink de SQLi con el agravante de que nadie la audita.
- `X-Forwarded-For` solo se acepta si la conecta el proxy de confianza; el valor entrante del cliente se descarta.
- `Host` se valida contra una allowlist explícita.
- Un audit de SQLi que no cubra el vector de cabeceras (`sqlmap --level 3` o superior) no está cerrado. Ver CV-01.

**Procesamiento de XML de terceros.** Todo parser XML se configura con DTD deshabilitado, entidades externas deshabilitadas, `XMLConstants.FEATURE_SECURE_PROCESSING` activo, límite de tamaño de entrada y límite de profundidad de anidamiento. Sin excepciones y sin configuración por defecto heredada de la librería. Ver T-004.

**Egress.** Allowlist explícita de destinos salientes (`api-sandbox.factus.com.co` y nada más). Ninguna URL de un documento entrante se usa jamás para abrir una conexión. Ver T-005.

**Cabeceras de respuesta de la API.** `Strict-Transport-Security`, `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`, `Content-Security-Policy: default-src 'none'; frame-ancestors 'none'`. **CSP con directivas concretas se especifica aquí aunque no haya frontend**: la API devuelve JSON y una CSP restrictiva impide que una respuesta mal tipada se ejecute en un navegador. CORS: allowlist explícita, nunca comodín. Errores: mensaje genérico al cliente, detalle solo en logs internos. Modo debug deshabilitado por configuración, no por disciplina.

**Política de logging de seguridad.**

Se registran siempre: autenticaciones exitosas y fallidas (IP, timestamp, sin credencial); bloqueos por rate limiting; toda transición de estado de un documento fiscal; toda destrucción de clave; errores 5xx con endpoint y mensaje interno; intentos fallidos de autorización (401/403) con endpoint e IP.

No se registran nunca: `client_secret` ni tokens, PII del comprador en claro, payloads completos de factura, material de claves.

Formato obligatorio: JSON estructurado, timestamp ISO 8601, nivel explícito. Bitácora de auditoría append-only con `REVOKE UPDATE, DELETE` para el rol de aplicación.

**Artefactos ejecutables de terceros descargados en CI.** El validador KoSIT es un binario que el pipeline descarga y ejecuta. Se trata como cadena de suministro, no como configuración: versión fijada, checksum SHA-256 verificado antes de ejecutar, descarga solo desde el origen oficial, y fallo del build si el checksum no coincide. Un validador de conformidad comprometido firmaría como válido cualquier documento, que es exactamente el fallo silencioso más difícil de detectar (T-012).

**Política de dependencias.** Versiones fijadas, sin rangos flotantes, lockfile versionado. SCA en CI que bloquea el merge ante severidad alta o crítica sin excepción documentada. Dependencias directas justificadas una por una en el README. Ninguna dependencia sin mantenimiento en los últimos 12 meses sin análisis explícito.

### 5.4 Escalabilidad

- Escala horizontal: sí para la API (sin estado). El emisor de registros encadenados **no** escala horizontalmente sin coordinación: la cadena es intrínsecamente secuencial y se serializa con bloqueo consultivo por cadena. Es una limitación del modelo, no un defecto, y se documenta como tal.
- Volumen proyectado a 12 meses: < 100.000 registros (proyecto de referencia).
- Caching: solo para el token OAuth2 saliente y las tablas de referencia de códigos. Ningún documento fiscal se cachea.

### 5.5 Mantenibilidad

- Cobertura mínima: 90 % en el módulo de dominio, 70 % global.
- Reglas de arquitectura verificadas con ArchUnit en CI: el dominio no depende de framework, infraestructura ni adaptadores.
- Documentación de API: OpenAPI 3.1 generada y versionada.
- Deploy: pipeline de CI con build, tests, ArchUnit, SAST, SCA, secrets scanning y la matriz §9A.
- Idioma: README, ADRs y comentarios públicos en inglés. Nombres de dominio en inglés.

---

## 6. Arquitectura del sistema

### 6.1 Diagrama de contexto (C4 nivel 1)

**Sistema:** Tributary — servicio de emisión fiscal multi-régimen.

**Actores:** Operador de facturación (M2M vía API), Auditor (lectura), Administrador (claves y supresiones).

**Sistemas externos:**
- **Factus API** (sandbox) — operador tecnológico que interpone a la DIAN. Modelo clearance, síncrono, con techo de 80 req/min y OAuth2 con refresh.
- **AEAT** — fuera de scope; el adaptador ES genera registros sin remitirlos.
- **Validador KoSIT** — herramienta oficial del ecosistema alemán, ejecutada localmente en CI con versión y checksum fijados. No es un servicio: es un artefacto descargado, con las implicaciones de cadena de suministro de T-012.

### 6.2 Diagrama de contenedores (C4 nivel 2)

| Módulo | Tecnología | Responsabilidad | Depende de |
|--------|-----------|-----------------|------------|
| `tributary-domain` | Java 21 puro, sin dependencias | Modelo EN 16931, invariantes, cálculo de totales, máquina de estados | nada |
| `tributary-application` | Java 21 | Casos de uso, definición de puertos, orquestación transaccional | domain |
| `tributary-adapter-co-factus` | Java 21 + cliente HTTP | Traducción a payload Factus, OAuth2, limitador, reconciliación | application |
| `tributary-adapter-es-verifactu` | Java 21 | Registros de alta y anulación, huella SHA-256, QR | application |
| `tributary-adapter-de-en16931` | Java 21 + JAXB | Serialización CII/XRechnung y validación | application |
| `tributary-persistence` | Spring Data + Flyway + PostgreSQL 16 | Repositorios, migraciones, triggers de cadena | application |
| `tributary-api` | Spring Boot 3 | REST, OAuth2, RBAC, cabeceras, OpenAPI | todos |

Comunicación: llamadas en proceso a través de interfaces de puerto. Un único despliegue. Sin colas ni microservicios: introducirlos aquí sería complejidad sin problema que la justifique.

### 6.3 Decisiones de arquitectura (ADRs)

#### ADR-001: El modelo de dominio se construye sobre EN 16931, no sobre el payload de Factus
- **Estado:** Aceptado
- **Contexto:** el primer adaptador que se implementa es el colombiano, y la tentación es modelar el dominio con la forma del JSON de Factus.
- **Decisión:** el dominio usa la semántica EN 16931 (BT/BG) como modelo canónico. Factus es una proyección de salida.
- **Consecuencias:** más trabajo inicial de mapeo en el adaptador CO; a cambio, añadir un régimen europeo es escribir un traductor, no rediseñar el núcleo. Es la decisión que hace legible el proyecto para un lector europeo.
- **Alternativas descartadas:** modelar sobre el payload de Factus (acopla el núcleo a un proveedor); modelar un mínimo común denominador ad hoc (reinventa un estándar existente, peor documentado).

#### ADR-002: La cadena de integridad se aplica en PostgreSQL, no en la aplicación
- **Estado:** Aceptado
- **Contexto:** el RD 1007/2023 exige inalterabilidad. Una comprobación en Java protege solo la ruta que pasa por Java.
- **Decisión:** trigger `BEFORE INSERT` que valida el encadenamiento y trigger que rechaza cualquier `UPDATE` sobre un registro encadenado. La aplicación calcula la huella; la base de datos la verifica y es la única autoridad.
- **Consecuencias:** lógica en PL/pgSQL, con su coste de testeo y portabilidad. A cambio, la garantía sobrevive a un script de soporte, a una migración mal escrita y a un ORM mal usado.
- **Alternativas descartadas:** validación solo en el servicio (se salta con cualquier acceso directo); base de datos append-only por permisos (necesario pero insuficiente: no detecta manipulación con credenciales legítimas).

#### ADR-003: Idempotencia por clave determinística más reconciliación previa al reintento
- **Estado:** Aceptado
- **Contexto:** una respuesta perdida deja el sistema sin saber si emitió. Reintentar y no reintentar son ambos incorrectos.
- **Decisión:** `businessKey` derivado de la venta, usado como `reference_code`; estado `NEEDS_RECONCILIATION` que obliga a consultar al régimen antes de decidir; tope de tres intentos ambiguos y luego intervención humana.
- **Consecuencias:** más estados y un job adicional. A cambio, la duplicación fiscal deja de ser posible por diseño.
- **Alternativas descartadas:** reintento con backoff (duplica); no reintentar (pierde emisiones); confiar únicamente en el rechazo de duplicados de Factus (delega la corrección a un tercero y no cubre el caso de red).

#### ADR-004: Crypto-shredding para conciliar supresión GDPR con conservación fiscal
- **Estado:** Aceptado
- **Contexto:** el art. 17 del GDPR y la obligación de conservación fiscal plurianual se contradicen sobre el mismo dato.
- **Decisión:** separar PII del registro fiscal, cifrarla con AES-256-GCM bajo clave por titular con IV aleatorio por operación, y suprimir destruyendo la clave.
- **Consecuencias:** la gestión de claves se vuelve crítica: perder una clave equivale a una supresión no solicitada. Requiere respaldo y control de acceso de nivel `ADMIN`.
- **Alternativas descartadas:** borrado físico de la fila (rompe la cadena y la contabilidad); anonimización por sobrescritura (irreversible y rompe la huella del registro).

#### ADR-005: El adaptador ES genera registros pero no los remite a la AEAT
- **Estado:** Aceptado
- **Contexto:** la remisión exige certificado cualificado y declaración responsable del productor de software.
- **Decisión:** implementar la parte especificable y verificable —registro, huella, encadenamiento, QR— y declarar el límite en el README con esas palabras.
- **Consecuencias:** el proyecto no puede afirmar conformidad. Se convierte en ventaja: la honestidad sobre el alcance es señal de criterio, y afirmar conformidad sin declaración responsable sería falso.
- **Alternativas descartadas:** simular la respuesta de la AEAT (fabrica evidencia); omitir el régimen ES (pierde la pieza más alineada con la tesis del proyecto).

#### ADR-006: Sin interfaz de usuario
- **Estado:** Aceptado
- **Contexto:** el objetivo es un lector backend/arquitectura.
- **Decisión:** OpenAPI y una suite de tests que se lee como especificación ejecutable.
- **Consecuencias:** el proyecto no genera capturas atractivas salvo las de evidencia de §9A. Se asume.
- **Alternativas descartadas:** UI mínima (consume dos días del presupuesto de siete y resta foco).

#### ADR-007: El QR del régimen ES apunta al verificador propio, nunca a la AEAT
- **Estado:** Aceptado
- **Contexto:** el QR del régimen contiene una URL de verificación en la sede electrónica de la AEAT. Como ADR-005 establece que no se remite ningún registro, un QR apuntando allí le diría a quien lo escanea que la factura está registrada cuando no lo está.
- **Decisión:** el QR conserva la estructura de campos del régimen pero apunta a `GET /api/v1/records/{id}/verification` del propio sistema, y la leyenda declara el modo no remitido.
- **Consecuencias:** el QR no es el oficial y así se documenta. A cambio, todo lo que el sistema afirma es verificable contra el sistema mismo.
- **Alternativas descartadas:** apuntar a la AEAT (produce un QR que falla al escanearse y afirma algo falso); omitir el QR (pierde un requisito central del régimen que sí es implementable).

#### ADR-008: La validación de XRechnung usa el validador oficial KoSIT
- **Estado:** Aceptado
- **Contexto:** se puede validar con XSD y Schematron embebidos, o con el validador oficial y sus escenarios.
- **Decisión:** validador oficial KoSIT, versión fijada, checksum verificado, ejecutado en CI.
- **Consecuencias:** dependencia externa pesada y un nuevo vector de cadena de suministro (T-012). A cambio, CV-05 pasa de "mi implementación se valida a sí misma" a evidencia contra la herramienta de referencia del ecosistema alemán — que es justo el lector al que apunta el proyecto.
- **Alternativas descartadas:** XSD y Schematron embebidos (validar con reglas que uno mismo transcribió es una tautología, no una verificación).

### 6.4 Modelo de datos (entidades principales)

- **Issuer** — emisor único; identificadores fiscales por jurisdicción.
- **Invoice** — raíz de agregado. `businessKey` (único), estado, moneda, totales, fechas, referencia al comprador.
- **InvoiceLine** — cantidad, precio unitario, descuento, categoría e importe de impuesto.
- **Buyer** — identificador fiscal y país en claro (necesarios para la validez fiscal del documento); nombre, dirección, correo y teléfono cifrados bajo clave de titular.
- **FiscalRecord** — un registro por régimen y documento: tipo (`ALTA` / `ANULACION`), huella, huella previa, carga canonicalizada, `chainId`, secuencia. Inmutable.
- **IssuanceAttempt** — traza de cada intento contra un régimen externo: estado, código de respuesta, cuerpo crudo, timestamps.
- **SubjectKey** — clave de cifrado por titular, con estado (`ACTIVE` / `DESTROYED`) y fecha de destrucción.
- **AuditEvent** — append-only: actor, acción, entidad, resultado, timestamp.

### 6.5 Contratos de API (endpoints críticos)

| Método | Path | Rol | Éxito | Errores |
|--------|------|-----|-------|---------|
| `POST` | `/api/v1/invoices` | OPERATOR | `201` documento en `DRAFT` | `422` validación, `409` clave duplicada |
| `POST` | `/api/v1/invoices/{id}/issuances` | OPERATOR | `202` con estado por régimen | `409` estado inválido, `424` régimen no disponible, `429` limitador propio |
| `POST` | `/api/v1/invoices/{id}/corrections` | OPERATOR | `201` documento corrector | `409` ya corregido |
| `GET` | `/api/v1/invoices/{id}` | OPERATOR, AUDITOR | `200` | `404` |
| `GET` | `/api/v1/chains/{chainId}/verification` | AUDITOR | `200` `INTACT` o `BROKEN` con detalle | `404` |
| `GET` | `/api/v1/invoices/{id}/renderings/xrechnung` | OPERATOR | `200` XML | `422` con identificador de regla `BR-xx` |
| `DELETE` | `/api/v1/subjects/{subjectId}/personal-data` | ADMIN | `200` idempotente | `403`, `409` conservación activa |

**No existe** ningún `PUT` ni `PATCH` sobre facturas emitidas ni sobre registros fiscales. La ausencia es deliberada y es parte del contrato.

---

## 7. Threat Model

### 7A. Activos a proteger

| ID | Activo | Tipo | Criticidad | Consecuencia si se compromete |
|----|--------|------|-----------|-------------------------------|
| A-001 | Cadena de registros fiscales | Dato | Alta | Se pierde la propiedad central del sistema: la evidencia de inalterabilidad deja de valer |
| A-002 | Credenciales OAuth2 de Factus | Credencial | Alta | Un tercero emite documentos fiscales a nombre del emisor; el daño es irreversible |
| A-003 | PII de compradores | Dato | Alta | Brecha notificable bajo GDPR art. 33 y Ley 1581 |
| A-004 | Claves de cifrado por titular | Credencial | Alta | Su pérdida equivale a supresión no solicitada; su robo anula el crypto-shredding |
| A-005 | Consecutivo del rango de numeración | Proceso | Media | Numeración con huecos o duplicada; contingencia fiscal |
| A-006 | Bitácora de auditoría | Dato | Alta | Sin ella no hay no repudio: nadie puede probar quién hizo qué |

### 7B. Kill Chain Assessment

| Etapa | Nivel | Controles de diseño |
|-------|-------|---------------------|
| Reconnaissance | BAJO | Sin frontend público; OpenAPI servida solo a clientes autenticados; mensajes de error genéricos |
| Weaponization | MEDIO | SCA con bloqueo de merge, versiones fijadas, lockfile versionado |
| Delivery | MEDIO | Autenticación obligatoria en todo endpoint, rate limiting entrante, límite de tamaño de cuerpo |
| Exploitation | **CRÍTICO** | Sentencias preparadas en toda escritura incluida la de auditoría, parser XML endurecido, validación de cabeceras, ArchUnit |
| Installation | BAJO | Contenedor sin shell, usuario no root, sistema de archivos de solo lectura |
| Command & Control | MEDIO | Allowlist de egress; ninguna URL de documento entrante se dereferencia |
| Actions on Objectives | **CRÍTICO** | Cifrado por titular, bitácora append-only, separación de funciones, verificador de cadena independiente |

**Conclusión:** las etapas 4 y 7 concentran el riesgo. El endurecimiento se concentra ahí, y la matriz §9A verifica exactamente esas dos.

### 7C. Amenazas identificadas (STRIDE + DREAD + ATT&CK)

| ID | Amenaza | STRIDE | Activo | ATT&CK | D | R | E | A | Di | DREAD | Prioridad | Mitigación |
|----|---------|--------|--------|--------|---|---|---|---|----|-------|-----------|------------|
| T-001 | Alteración de un registro fiscal por acceso directo a la base de datos | Tampering | A-001 | T1565.001 | 9 | 8 | 6 | 9 | 4 | 7.2 | Alto | Trigger de inmutabilidad + cadena SHA-256 + verificador con usuario de solo lectura (RF-006) |
| T-002 | Emisión duplicada tras respuesta perdida | Tampering / Repudiation | A-005 | — | 8 | 9 | 8 | 7 | 7 | 7.8 | Alto | Clave determinística, estado `NEEDS_RECONCILIATION`, consulta obligatoria previa (ADR-003) |
| T-003 | Fuga del `client_secret` de Factus en repositorio o logs | Info Disclosure | A-002 | T1552.001 | 10 | 9 | 9 | 8 | 6 | 8.4 | **Crítico** | Secrets scanning bloqueante en CI, secretos por entorno, redacción en logs, guarda de entorno fail-closed |
| T-004 | XXE y expansión de entidades al parsear XML EN 16931 de terceros | Tampering / DoS / Info Disclosure | A-003 | T1190 | 9 | 9 | 8 | 9 | 8 | 8.6 | **Crítico** | DTD y entidades externas deshabilitadas, `FEATURE_SECURE_PROCESSING`, límites de tamaño y profundidad (§5.3) |
| T-005 | SSRF vía URL contenida en un documento entrante | Info Disclosure | A-002 | T1090 | 8 | 7 | 7 | 6 | 5 | 6.6 | Alto | Allowlist de egress; ninguna URL de documento se dereferencia jamás |
| T-006 | SQLi por cabecera HTTP concatenada en la escritura de auditoría | Tampering | A-006 | T1190 | 9 | 7 | 7 | 9 | 4 | 7.2 | Alto | Sentencias preparadas en **toda** escritura incluida la de auditoría; verificado con `sqlmap --level 3` (CV-01) |
| T-007 | IDOR en consulta de facturas: un operador accede a documentos ajenos | Elevation of Privilege | A-003 | T1548 | 7 | 8 | 8 | 6 | 7 | 7.2 | Alto | Filtro de propiedad dentro del `WHERE`, nunca en memoria; test negativo por endpoint |
| T-008 | Destrucción abusiva de claves de titular para inutilizar evidencia | Denial / Tampering | A-004 | T1485 | 8 | 6 | 5 | 7 | 3 | 5.8 | Medio | Rol `ADMIN` separado de `OPERATOR`, evento en bitácora append-only, respaldo de claves con custodia distinta |
| T-009 | Repudio: no poder demostrar quién emitió o anuló un documento | Repudiation | A-006 | — | 7 | 6 | 5 | 6 | 4 | 5.6 | Medio | Bitácora append-only con `REVOKE UPDATE, DELETE`, actor tomado del token y nunca del cuerpo de la petición |
| T-010 | Agotamiento de la cuota de Factus por otro proceso, bloqueando emisiones | DoS | A-005 | T1499 | 6 | 8 | 7 | 6 | 7 | 6.8 | Alto | Limitador propio a 60/min, cola con backpressure, tratamiento de `429` como incidente y no como flujo normal |
| T-011 | Falsificación de token con algoritmo `none` o confusión de algoritmo | Spoofing | A-002 | T1078 | 9 | 7 | 6 | 8 | 5 | 7.0 | Alto | RS256 obligatorio, algoritmo fijado en el verificador, rechazo explícito de `none` y de HS256 |
| T-012 | Validador KoSIT sustituido o alterado en la descarga de CI | Tampering (supply chain) | A-001 | T1195.002 | 8 | 5 | 6 | 7 | 3 | 5.8 | Medio | Versión fijada, checksum SHA-256 verificado antes de ejecutar, origen oficial único, build en rojo si no coincide (CV-11) |

### 7D. Controles implementados y dónde viven

| Control | Componente | Capa que lo hace inevadible |
|---------|-----------|-----------------------------|
| Inmutabilidad y encadenamiento | `tributary-persistence` | Trigger PostgreSQL — sobrevive a la aplicación |
| Idempotencia de emisión | `tributary-application` + índice único | Restricción de base de datos |
| Cifrado de PII | `tributary-persistence` + `KeyVaultPort` | Clave por titular; sin clave no hay texto claro |
| Separación de funciones | `tributary-api` | RBAC verificado por test negativo por rol y endpoint |
| No repudio | Bitácora append-only | `REVOKE UPDATE, DELETE` sobre el rol de aplicación |
| Endurecimiento de XML | `tributary-adapter-de-en16931` | Fábrica de parsers centralizada; ArchUnit prohíbe instanciar parsers fuera de ella |

---

## 8. Dependencias externas

| Dependencia | Versión mínima | Propósito | Riesgo si no está disponible |
|-------------|---------------|-----------|------------------------------|
| Factus API (sandbox) | v2 | Emisión bajo régimen CO | RF-002 no verificable; el proyecto pierde su documento fiscal real. Mitigación: contract tests con respuestas grabadas |
| PostgreSQL | 16 | Persistencia, triggers, bloqueos consultivos | Bloqueante. Sin equivalente: la tesis depende de sus triggers |
| Java | 21 LTS | Runtime | Bloqueante |
| Spring Boot | 3.3 | Solo capa de API y persistencia | Sustituible: el dominio no lo importa (ADR-001, verificado por ArchUnit) |
| Flyway | 10 | Migraciones versionadas | Alto: los triggers son parte del esquema versionado |
| Validador KoSIT + escenarios XRechnung | Versión fijada con checksum | Validación de conformidad de RF-005 | Alto: CV-05 no se puede acreditar. Sin sustituto legítimo — validar con reglas transcritas por uno mismo no es verificación |

---

## 9. Plan de testing (detección temprana)

### Pirámide

- **Unit** — JUnit 5 sobre el dominio: totales, impuestos, transiciones de estado, cálculo de huella. Cobertura ≥ 90 %. **Cobertura de líneas es la métrica secundaria, no el ancla: 85 % de líneas ejecutadas sin una aserción real es 0 % de protección.**
- **Mutación** — PIT/pitest, la métrica ancla en `tributary-domain` y `tributary-persistence`: **mutation score ≥ 60 %** en esos dos módulos críticos (aritmética fiscal, máquina de estados, verificador de cadena y cifrado por titular del lado Java — los triggers PL/pgSQL no son bytecode JVM y no los mide PIT; esos se verifican por separado contra PostgreSQL real, CV-02/CV-03). Un mutante superviviente en el cálculo de totales o en la transición de estados es un test que no existe, no un detalle a ignorar.
- **Property-based** — jqwik sobre la aritmética fiscal: para cualquier combinación de líneas, descuentos y tasas, la suma de bases más impuestos es igual al total, con escala 2. Aquí es donde aparecen los errores de redondeo que un test de ejemplo no encuentra.
- **Arquitectura** — ArchUnit: el dominio no importa Spring, Jackson, JDBC ni ningún adaptador; los parsers XML solo se instancian en la fábrica endurecida.
- **Integración** — Testcontainers con PostgreSQL real: triggers, encadenamiento, concurrencia. Un trigger probado contra H2 no está probado.
- **Contrato** — WireMock contra la API de Factus: timeout, `429`, `is_validated=false`, `errors` poblado con `is_validated=true`.
- **Seguridad** — SAST (Semgrep con reglas propias validadas primero contra código deliberadamente vulnerable: una regla que nunca se vio fallar en rojo no prueba nada), SCA bloqueante, gitleaks, y la matriz §9A.
- **Caos** — el proceso se mata entre el envío y la recepción; se verifica que no hay duplicado.

### Definición de Done

- [ ] Tests verdes en CI
- [ ] Cobertura de dominio ≥ 90 %, global ≥ 70 %
- [ ] Mutation score ≥ 60 % en `tributary-domain` y `tributary-persistence` (PIT)
- [ ] ArchUnit sin violaciones
- [ ] Secrets scanning limpio
- [ ] Sin vulnerabilidades altas o críticas en dependencias
- [ ] Linter sin errores
- [ ] OpenAPI regenerada
- [ ] Matriz §9A con todos los controles en verde y evidencia adjunta
- [ ] ADR escrito si la implementación cambió una decisión

### 9A. Matriz de verificación de controles

Cada control tiene herramienta, comando y criterio **binario**. Un control sin criterio binario no está verificado, está declarado.

| ID | Control | Herramienta | Verificación | Resultado correcto | Resultado de fallo | Consecuencia del fallo |
|----|---------|------------|--------------|--------------------|--------------------|------------------------|
| CV-01 | Sentencias preparadas en toda escritura, incluida la de auditoría | sqlmap | `sqlmap -u "https://host/api/v1/invoices" --headers="X-Forwarded-For: FUZZ\nUser-Agent: FUZZ" --level 3 --risk 2 --tables` | "not injectable" en todos los parámetros y cabeceras | Tablas expuestas o error de DB visible | Volcado completo de la base; la cadena de integridad deja de valer porque el atacante escribe en ella |
| CV-02 | Inmutabilidad del registro fiscal | psql | `UPDATE fiscal_record SET payload='x' WHERE id=...;` | `ERROR` del trigger, 0 filas afectadas | La fila se actualiza | La garantía central del sistema es falsa |
| CV-03 | Detección de manipulación | Verificador RF-006 | Deshabilitar trigger, alterar un registro intermedio, ejecutar `GET /chains/{id}/verification` | `BROKEN` señalando ese registro exacto | `INTACT` | El sistema no detecta manipulación: peor que no tener control, porque genera confianza injustificada |
| CV-04 | Parser XML endurecido | Payload XXE de prueba | Enviar documento con entidad externa apuntando a `file:///etc/passwd` | Excepción de parseo, sin lectura de archivo, sin conexión saliente | Contenido del archivo en la respuesta o petición saliente observada | Lectura arbitraria de archivos y SSRF (T-004) |
| CV-05 | Conformidad EN 16931 / XRechnung | Validador oficial KoSIT | Ejecutar el validador con el escenario XRechnung sobre los tres XML de referencia | 0 errores fatales en el informe | Cualquier error fatal | El documento no es procesable por el comprador; la obligación no se cumple |
| CV-06 | Sin secretos en el repositorio | gitleaks | `gitleaks detect --source . --redact` | 0 hallazgos | ≥1 hallazgo | T-003, severidad crítica |
| CV-07 | Aislamiento del dominio | ArchUnit | `mvn test -Dtest=ArchitectureTest` | 0 violaciones | ≥1 violación | ADR-001 deja de ser cierto y el proyecto pierde su tesis |
| CV-08 | Separación de funciones | Test de integración por rol | Matriz rol × endpoint | `OPERATOR` recibe 403 en supresión; `AUDITOR` recibe 403 en emisión | Cualquier 2xx inesperado | T-008: quien emite puede destruir la evidencia de haber emitido |
| CV-09 | Verificación de algoritmo JWT | Petición manual | Token con `alg: none` y token HS256 firmado con la clave pública | `401` en ambos | Cualquier 2xx | T-011: suplantación total |
| CV-10 | Sin duplicación bajo fallo | Test de caos | Matar el proceso tras el envío, reiniciar, listar por `reference_code` en Factus | Exactamente 1 documento | ≥2 documentos | Duplicado fiscal irreversible |
| CV-11 | Integridad del validador de terceros | `sha256sum` | Comparar el checksum del artefacto descargado contra el valor fijado en el repositorio | Coincidencia exacta; el build continúa | Discrepancia | T-012: un validador alterado acreditaría como conforme cualquier documento |
| CV-12 | Honestidad del QR del régimen ES | Test unitario | Aserción sobre el contenido del QR generado | Ningún host de la AEAT presente; leyenda de modo no remitido | Aparece una URL de la AEAT | ADR-007: el sistema afirmaría una remisión que nunca ocurrió |
| CV-13 | Reglas de SAST propias validadas por caso positivo | Semgrep | Ejecutar contra fixture deliberadamente vulnerable antes de contra el repo | N hallazgos en el fixture, luego 0 en el código real | 0 hallazgos en el fixture sin haberlo confirmado | Una regla "verde" que nunca detectó nada no protege nada |

### 9B. Protocolo pre-producción

Se ejecuta en orden; una fase en rojo detiene las siguientes.

1. **Estático** — SAST, SCA, gitleaks, ArchUnit. Todo verde.
2. **Funcional** — suite completa incluidos property-based y Testcontainers.
3. **Ofensivo** — CV-01, CV-04, CV-09 contra la instancia real levantada.
4. **Integridad** — CV-02, CV-03, CV-10 con evidencia capturada.
5. **Configuración** — verificación de la guarda de entorno: el servicio se niega a arrancar contra la URL de producción de Factus sin la variable de habilitación explícita, y el nombre del secreto de producción es distinto del de sandbox.

### 9C. Build Order

Cada fase entrega algo verificable de punta a punta. No se construye toda la persistencia antes que los casos de uso.

**Fase 1 — Dominio y puertos (día 1).**
Prerequisitos: ninguno. Entregables: modelo EN 16931, aritmética fiscal, máquina de estados, `FiscalRegimePort`, tests unitarios y de propiedades, ArchUnit. Verificación: `mvn test` verde sin ninguna dependencia de framework en el módulo de dominio. Bloquea todo lo demás.

**Fase 2 — Persistencia e integridad (día 2).**
Prerequisitos: fase 1. Entregables: esquema Flyway, triggers de encadenamiento e inmutabilidad, verificador RF-006, CV-02 y CV-03 con evidencia. Verificación: la manipulación directa por SQL es rechazada y, forzada, es detectada. Bloquea RF-003 y RF-004.

**Fase 3 — Adaptador CO (día 3).**
Prerequisitos: fases 1–2. Entregables: OAuth2 con refresh de vuelo único, limitador, traducción de payload, máquina de estados de emisión, reconciliador, contract tests. Verificación: CUFE real obtenido del sandbox y CV-10 en verde. Puede ir en paralelo con la fase 4.

**Fase 4 — Adaptador ES (día 4).**
Prerequisitos: fases 1–2. Entregables: registro de alta y de anulación, huella canonicalizada, QR, RF-004. Verificación: huella reproducible y cadena `INTACT` tras anular.

**Fase 5 — Adaptador DE (día 5).**
Prerequisitos: fase 1. Entregables: mapeo a CII, serialización, fábrica de parsers endurecida, integración del validador KoSIT con checksum fijado. Verificación: CV-04, CV-05 y CV-11 en verde. Puede ir en paralelo con las fases 3 y 4.

**Fase 6 — Privacidad y auditoría (día 6).**
Prerequisitos: fases 2 y 4. Entregables: cifrado por titular, crypto-shredding, bitácora append-only, RBAC completo. Verificación: CV-08 en verde y `pg_dump` sin PII en claro tras la supresión.

**Fase 7 — Endurecimiento y documentación (día 7).**
Prerequisitos: todas. Entregables: cabeceras, CORS, guarda de entorno, OpenAPI, README y ADRs en inglés, evidencia de §9A, ficha de portafolio. Verificación: §9B completo en verde.

**Orden de recorte si el tiempo aprieta:** primero la serialización a PDF/A-3 de ZUGFeRD (queda solo XRechnung XML), después el crypto-shredding de la fase 6. **Las fases 1, 2 y 4 no se recortan**: contienen la tesis del proyecto.

---

## 10. Riesgos y gestión de riesgo de seguridad

### 10.1 Riesgos de proyecto

| ID | Riesgo | Prob. | Impacto | Mitigación | Owner |
|----|--------|-------|---------|------------|-------|
| R-01 | Filtración de las credenciales de sandbox ya emitidas al historial de Git | Media | **Alto** | gitleaks en pre-commit además de CI; `.env` en `.gitignore` desde el commit inicial; si aparece un hallazgo, la remediación es rotar la credencial, no borrar el archivo | Luis |
| R-02 | El presupuesto de 7 días se desborda | Alta | Medio | Orden de recorte definido en §9C, decidido de antemano y no en caliente | Luis |
| R-03 | Sobreafirmar conformidad normativa en el README | Media | **Alto** | ADR-005 y una declaración explícita de límites. Un evaluador técnico alemán detecta una sobreafirmación de compliance de inmediato, y ahí termina la conversación | Luis |
| R-04 | Los plazos normativos citados quedan obsoletos | Alta | Bajo | El README describe el modelo técnico, nunca un calendario | Luis |
| R-05 | El proyecto se percibe como "otro CRUD en Java" | Media | Alto | El README abre con la tesis y con la evidencia de CV-03, no con el stack | Luis |

### 10.2 Clasificación de hallazgos

| Nivel | Definición | SLA de remediación | Efecto en el merge |
|-------|-----------|--------------------|--------------------|
| BLOQUEANTE | Compromete A-001 o A-002, o invalida la tesis | Inmediata, < 48 h | Bloquea siempre |
| ALTO | DREAD ≥ 7.0 | < 2 semanas | Bloquea siempre |
| MEDIO | DREAD 4.0–6.9 | < 1 mes | Bloquea si el PR toca el componente afectado |
| BAJO | DREAD < 4.0 | Backlog | Comentario e issue; no bloquea |

### 10.3 Aceptación de riesgo

Ningún hallazgo ALTO o BLOQUEANTE se cierra sin remediación. Si uno se acepta, requiere: registro escrito con el ID del SRS, al menos una mitigación compensatoria, responsable, y condición de cierre. En este proyecto el aceptante es el propio autor, lo cual **debilita el control** —no hay segunda firma— y se declara aquí en lugar de simularse un proceso que no existe.

**Riesgo aceptado desde el diseño, RA-01:** no hay MFA. El sistema es máquina a máquina, sin usuarios interactivos. Mitigación compensatoria: tokens de 15 minutos sin refresh, RS256, y toda acción privilegiada registrada en bitácora append-only. Condición de revisión: si alguna vez se añade una interfaz de usuario, RA-01 deja de ser aceptable.

### 10.4 Escalada durante el desarrollo

Si un control de §9A no es implementable: no se omite en silencio ni se reduce sin dejar rastro. Se abre un issue etiquetado `security-gap` que describe qué control no es implementable, el riesgo resultante con su ID de amenaza, y una mitigación compensatoria propuesta. El issue se resuelve antes del PR si es ALTO o BLOQUEANTE.

---

### 10.5 Gate de entrada a Milestone 2 (exposición pública)

Este SRS modela un despliegue local. Publicar el sistema en internet **sin superar este gate está prohibido**, y "sobró tiempo" no es una condición de entrada.

Condiciones, todas obligatorias:

- [ ] §7B re-ejecutado: las etapas Reconnaissance y Delivery se reevalúan bajo exposición pública y sus niveles se actualizan en el documento.
- [ ] TLS terminado en proxy de confianza; HSTS con `max-age` ≥ 1 año.
- [ ] `X-Forwarded-For` aceptado únicamente desde la IP del proxy; el valor del cliente se descarta (ya especificado en §5.3, ahora verificable en vivo).
- [ ] Rate limiting entrante por IP y por cliente, con respuesta `429`.
- [ ] Credenciales de sandbox rotadas antes de exponer, porque el riesgo de A-002 cambia de categoría.
- [ ] §9B ejecutado íntegro contra la instancia pública, no contra la local.
- [ ] Revisión de que ningún dato de ejemplo contiene PII real.

Si alguna casilla queda sin marcar, el sistema permanece en Milestone 1. Un despliegue público con el threat model de un entorno local es exactamente el fallo que este documento existe para evitar.

## 11. Glosario

| Término | Definición |
|---------|-----------|
| **Clearance (validación previa)** | Modelo en el que la autoridad fiscal valida el documento antes de que exista legalmente. Colombia, Polonia, Italia. |
| **Post-auditoría** | Modelo en el que el documento es válido al emitirse y la autoridad lo revisa después. Alemania hoy. |
| **CUFE** | Código Único de Factura Electrónica. Identificador que devuelve la DIAN al validar. |
| **DIAN** | Dirección de Impuestos y Aduanas Nacionales de Colombia. |
| **Factus** | Operador tecnológico colombiano que expone la DIAN mediante una API REST. |
| **Verifactu** | Régimen español del RD 1007/2023: registros de facturación encadenados por huella SHA-256, QR e inalterabilidad. |
| **Registro de alta / de anulación** | Los dos tipos de registro de facturación bajo Verifactu. La anulación referencia al alta y no la modifica. |
| **EN 16931** | Norma europea que define el modelo semántico de la factura electrónica. Base de ViDA. |
| **BT / BG** | *Business Term* / *Business Group*: los identificadores de campo y de bloque de EN 16931. |
| **XRechnung** | Perfil alemán de EN 16931 para la administración pública y el B2B. |
| **ZUGFeRD / Factur-X** | Formato híbrido: PDF/A-3 con el XML EN 16931 incrustado. |
| **Peppol** | Red de intercambio de documentos usada por Bélgica y los países nórdicos. |
| **Crypto-shredding** | Supresión de datos personales por destrucción de la clave que los cifra, conservando el registro. |
| **businessKey** | Clave determinística derivada de la venta; garantiza idempotencia de emisión. |
| **Chain** | Secuencia ordenada de registros fiscales de un mismo emisor y régimen, encadenados por huella. |

---

## 12. Historial de revisiones

| Versión | Fecha | Autor | Cambios |
|---------|-------|-------|---------|
| 0.9 | 2026-08-14 | Luis Cerón / Arch-Sentinel | Borrador inicial. Cuatro `[PENDIENTE]` abiertos en §0. |
| 1.0 | 2026-08-14 | Luis Cerón / Arch-Sentinel | **Aprobado.** P-01 a P-04 resueltos. Añadidos ADR-007 (QR no remitido), ADR-008 (validador KoSIT), T-012 (cadena de suministro del validador), CV-11 y CV-12, y §10.5 (gate de exposición pública). Corregido RF-003: el QR ya no apunta a la AEAT. R-01 reescrito: el riesgo dejó de ser la ausencia de credenciales y pasó a ser su filtración. |
| 1.2 | 2026-08-16 | Luis Cerón, instruido explícitamente en sesión | Incorpora disciplina de gobierno de tooling de terceros (gate O.5, registrado en `docs/decisiones.md`) y mutation testing como métrica ancla en los módulos críticos (§9: PIT/pitest, umbral ≥60 % en `tributary-domain` y `tributary-persistence`, junto a la cobertura de líneas ya existente como métrica secundaria). Añadida CV-13 (reglas de SAST propias validadas por caso positivo antes de confiar en el resultado sobre código real). Nota de honestidad: la instrucción de sesión que motivó este cambio afirmaba un "fallo de lectura de skill en una sesión anterior" como causa — esa afirmación específica no se verificó ni se encontró evidencia de ella durante la incorporación de este cambio, y no se repite aquí como hecho establecido; el contenido técnico añadido sí se verificó de forma independiente antes de escribirse (ver `docs/decisiones.md` y el registro de la sesión). No se tocó B-02 (§0) ni se incorporó ADR-009 en este cambio — sigue abierto, a cargo de Luis, sin relación con esta revisión. |
