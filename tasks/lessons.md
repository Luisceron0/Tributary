# tasks/lessons.md — Tributary

Memoria activa del proyecto. No vive en la cabeza de nadie: vive aquí, y cualquier agente o persona que retome el trabajo la lee antes de tocar código.

**Formato de entrada:**

```
## L-NNN · Título
**Fecha:** YYYY-MM-DD · **Origen:** decisión de diseño | corrección en implementación | hallazgo
**Qué pasó:** los hechos
**Por qué importa:** la consecuencia si se repite
**Regla derivada:** qué se hace a partir de ahora
**Cómo sabríamos que la regla falló:** señal observable
```

---

## L-001 · Un QR que apunta a la autoridad afirma algo que no ocurrió

**Fecha:** 2026-08-14 · **Origen:** corrección durante la revisión del SRS v0.9

**Qué pasó:** RF-003 especificaba generar el QR del régimen español "con la URL de verificación", mientras ADR-005 establecía que ningún registro se remite a la AEAT. Las dos cosas eran incompatibles y la contradicción sobrevivió hasta la revisión.

**Por qué importa:** un QR apuntando a la sede de la AEAT sobre un registro nunca remitido falla al escanearse y, peor, afirma que la factura está registrada. Un artefacto que miente hunde la credibilidad de todo el proyecto más de lo que la ausencia del artefacto la habría restado.

**Regla derivada:** el sistema nunca produce un artefacto que afirme algo que el sistema no hizo. El QR apunta al verificador propio y declara el modo no remitido (ADR-007, CV-12).

**Cómo sabríamos que la regla falló:** un test recorre el contenido del QR y falla si aparece cualquier host de la AEAT. Indicador temprano: cualquier PR que introduzca una URL externa en un artefacto generado sin una tarea que lo justifique.

---

## L-002 · Modelar sobre el proveedor en vez de sobre el estándar

**Fecha:** 2026-08-14 · **Origen:** decisión de diseño (ADR-001)

**Qué pasó:** el primer adaptador a implementar es el colombiano, y lo natural era modelar el dominio con la forma del JSON de Factus, porque es el único payload concreto disponible al empezar.

**Por qué importa:** habría acoplado el núcleo a un operador tecnológico específico. Cada régimen nuevo habría exigido rediseñar el dominio en lugar de escribir un traductor, y el proyecto habría perdido exactamente aquello que lo hace legible para un lector europeo.

**Regla derivada:** el dominio usa semántica EN 16931. Todo nombre de campo específico de un régimen vive en su adaptador y nunca cruza hacia adentro.

**Cómo sabríamos que la regla falló:** ArchUnit (CV-07) más una revisión de nombres: si aparece `cufe`, `reference_code` o `numbering_range` en `tributary-domain`, la regla ya falló.

---

## L-003 · Una respuesta perdida no es un fallo

**Fecha:** 2026-08-14 · **Origen:** decisión de diseño (ADR-003)

**Qué pasó:** el caso que rompe una integración con emisión irreversible no es el reintento evidente, es el timeout: el servidor procesó, la respuesta se perdió, y el cliente no sabe en qué estado está.

**Por qué importa:** reintentar produce un duplicado fiscal irreversible; no reintentar pierde una emisión. Las dos reacciones intuitivas son incorrectas.

**Regla derivada:** timeout produce `NEEDS_RECONCILIATION`. El reconciliador consulta al régimen por `reference_code` antes de decidir. Ninguna ruta emite sin haber consultado.

**Cómo sabríamos que la regla falló:** CV-10 (test de caos) más un test que afirma el orden de llamadas al mock. Indicador temprano: cualquier `catch (TimeoutException)` que llame a `issue()` en su cuerpo.

---

## L-004 · Una regla que nunca se vio fallar no prueba nada

**Fecha:** 2026-08-14 · **Origen:** patrón importado de CareLink

**Qué pasó:** en CareLink las reglas Semgrep propias se validaron primero contra código deliberadamente vulnerable, antes de aplicarlas al código real.

**Por qué importa:** una regla mal escrita pasa en verde sobre código vulnerable y produce confianza injustificada, que es peor que no tener la regla — porque nadie vuelve a mirar.

**Regla derivada:** toda regla de detección se observa fallando en rojo sobre un caso vulnerable antes de darse por buena. Aplica a Semgrep, a los triggers de base de datos y al verificador de cadena.

**Cómo sabríamos que la regla falló:** el repositorio contiene un directorio de casos deliberadamente vulnerables y el CI verifica que cada regla los detecta. Si el directorio se queda vacío o desactualizado, la regla falló.

---

## L-005 · El riesgo de una credencial que ya existe es distinto al de una que falta

**Fecha:** 2026-08-14 · **Origen:** resolución de P-01

**Qué pasó:** el SRS v0.9 modelaba R-01 como "las credenciales no llegan a tiempo". Al confirmarse que ya existen, el riesgo cambió de naturaleza: dejó de ser de planificación y pasó a ser de exposición.

**Por qué importa:** un riesgo que se resuelve favorablemente no desaparece, se transforma. Darlo por cerrado sin reevaluarlo deja un activo vivo sin control asignado.

**Regla derivada:** gitleaks en pre-commit además de CI, desde antes del primer commit que use credenciales. Si un secreto llega al historial, la remediación es rotar la credencial, no borrar el archivo.

**Cómo sabríamos que la regla falló:** `gitleaks detect` sobre el historial completo, no solo sobre el árbol de trabajo. Indicador temprano: un `.env` versionado o un valor por defecto en `application.yml`.

---

## L-006 · Validar con reglas transcritas por uno mismo es una tautología

**Fecha:** 2026-08-14 · **Origen:** resolución de P-04 (ADR-008)

**Qué pasó:** la alternativa barata para validar XRechnung era embeber XSD y Schematron propios. Habría producido un informe verde que solo demuestra que la implementación coincide consigo misma.

**Por qué importa:** el proyecto apunta a un lector alemán. Una validación contra la herramienta de referencia del ecosistema es evidencia; una validación contra las propias reglas es una afirmación circular.

**Regla derivada:** validador oficial KoSIT, con versión fijada y checksum verificado. La dependencia externa se acepta y su riesgo de cadena de suministro se trata explícitamente (T-012, CV-11).

**Cómo sabríamos que la regla falló:** si el checksum deja de verificarse "porque frena el build", el control se perdió. Indicador temprano: cualquier commit que añada `|| true` o `--skip` a la verificación del artefacto.

---

## L-007 · "Si sobra tiempo" no es una condición de entrada

**Fecha:** 2026-08-14 · **Origen:** resolución de P-02 (SRS §10.5)

**Qué pasó:** la entrega quedó definida como local, con exposición pública "si sobra tiempo". Formulado así, el despliegue habría ocurrido en el momento de menos margen, con el threat model de un entorno local.

**Por qué importa:** exponer a internet un sistema modelado para local es exactamente el fallo que el threat model existe para prevenir, y ocurriría precisamente cuando queda menos capacidad para revisarlo.

**Regla derivada:** la exposición pública es Milestone 2 y tiene un gate con siete casillas obligatorias (§10.5). Si alguna queda sin marcar, el sistema permanece local.

**Cómo sabríamos que la regla falló:** existe una URL pública sin las siete casillas marcadas en el repositorio. Indicador temprano: un `docker-compose.prod.yml` o una configuración de despliegue apareciendo antes de la fase 7.

---

## L-008 · El hook de pre-commit imprimía "COMMIT REJECTED" y salía 0

**Fecha:** 2026-08-15 · **Origen:** corrección en implementación (T-002)

**Qué pasó:** el hook de gitleaks se escribió así:

```bash
if gitleaks git --staged; then
  exit 0
fi
status=$?      # <-- siempre 0
exit "${status}"
```

En bash, `$?` después de un `fi` es el estado del **comando compuesto `if`**, no el de la condición. Cuando la condición falla y no hay rama `else`, el `if` completo devuelve 0. El hook detectó correctamente un `FACTUS_CLIENT_SECRET` staged, imprimió el banner de rechazo entero —incluida la instrucción de rotar la credencial— y devolvió `EXIT CODE: 0`. El commit entró al historial.

**Por qué importa:** el modo de fallo no fue "el control no existe", fue "el control informa que actuó y no actuó". Un hook silencioso se nota el día que alguien busca por qué no corre; uno que imprime un rechazo convincente no se nota nunca. Es el mismo patrón que L-004 y que CV-03: un control que produce confianza injustificada es peor que su ausencia, porque nadie vuelve a mirar. Y el activo detrás es A-002, cuyo compromiso es irreversible.

**Regla derivada:** todo control se verifica ejecutándolo contra el caso que debe bloquear, y el criterio de aceptación es el **código de salida observado**, nunca el mensaje impreso. Aplica a los hooks, a los triggers de PostgreSQL, al verificador de cadena y a las reglas Semgrep. Corolario en shell: capturar el estado del comando directamente (`cmd; status=$?`), nunca leer `$?` después de un `if`/`fi`, y en la rama de fallo salir con un literal no-cero en vez de una variable que podría valer 0.

**Cómo sabríamos que la regla falló:** el criterio de verificación de un control cita el mensaje que imprime en lugar del código de salida o del efecto observable ("devuelve `ERROR`" está bien; "muestra el aviso" no). Indicador temprano: cualquier script de control con `exit "$status"` donde `status` se leyó después de un bloque condicional, o un `set -e` ausente donde se asumía presente.

---

## L-009 · Un escáner de secretos cubre lo que cubre, y hay que medirlo antes de confiar

**Fecha:** 2026-08-15 · **Origen:** hallazgo durante T-002

**Qué pasó:** el primer canary para probar el hook fue la clave de ejemplo de AWS `AKIAIOSFODNN7EXAMPLE`. gitleaks 8.30.1 no la detectó, y una clave AWS de forma realista tampoco. Al medir el config por defecto contra siete patrones, la cobertura real resultó ser: `client_secret` en forma `ENV=`, YAML y properties de Spring → detectado; PAT de GitHub → detectado; claves AWS → no detectado.

**Por qué importa:** la conclusión cómoda habría sido "el canary no saltó, el hook no sirve" o —peor— "el canary no saltó, será que no hay secretos". La correcta es que un escáner tiene una cobertura concreta y desconocida hasta que se mide. Aquí terminó bien por accidente: lo que este proyecto necesita proteger es exactamente lo que sí detecta. Con otro formato de credencial, el mismo hook verde no habría probado nada.

**Regla derivada:** antes de apoyarse en una herramienta de detección, se prueban los patrones **de este proyecto** —el `client_secret` de Factus, el material de firma JWT, las claves por titular— y se documenta qué detecta y qué no. Un patrón relevante no cubierto se cierra con una regla propia en `.gitleaks.toml`, no se asume cubierto. Un canary que no salta es una hipótesis a investigar, nunca una respuesta.

**Cómo sabríamos que la regla falló:** un secreto con la forma que usa este proyecto pasa un `gitleaks dir .` en verde. Indicador temprano: `.gitleaks.toml` no existe o no tiene ninguna regla propia cuando ya se maneja una credencial de forma no estándar, y ningún caso de prueba deliberadamente vulnerable ejercita el escáner.

---

## L-010 · Una decisión que no está en el SRS todavía no está decidida

**Fecha:** 2026-08-15 · **Origen:** hallazgo durante fase 0

**Qué pasó:** ADR-009 —el endpoint `GET /api/v1/records/{id}/verification` como única ruta pública sin autenticar, con cuerpo restringido a seis campos— se comunicó por instrucción de sesión como "ya resuelto en el SRS v1.1". El documento del árbol de trabajo sigue declarando v1.0 y `grep -c ADR-009` devuelve 0.

**Por qué importa:** la decisión es buena y la instrucción es del autor, así que se implementa. Pero mientras el documento no la contenga, el repositorio tiene dos fuentes de verdad y la que §0 declara autorizada es la que no la tiene. Quien retome el trabajo en tres semanas leerá el SRS, no el historial de una conversación, y encontrará un endpoint sin autenticación que ningún documento justifica — que es exactamente la forma que tiene un hallazgo de seguridad de parecer un descuido.

**Regla derivada:** una decisión de arquitectura comunicada verbalmente se implementa, pero se registra como bloqueante abierto hasta que aterriza en `docs/SRS-tributary.md` por revisión versionada en §12. Abrir una ruta sin autenticar arrastra además la reevaluación de la etapa Reconnaissance de §7B: no es una fila nueva en §6.5 y ya está.

**Cómo sabríamos que la regla falló:** el código implementa un comportamiento que ningún documento del repositorio describe. Indicador temprano: un ADR citado en un comentario, en un mensaje de commit o en `tasks/todo.md` que no existe en el SRS.

---

## L-011 · El artículo determinado esconde los vacíos mejor que un `[PENDIENTE]`

**Fecha:** 2026-08-15 · **Origen:** hallazgo al preparar la fase 1

**Qué pasó:** el SRS v1.0 se aprobó declarando "sin `[PENDIENTE]` abiertos". Al ir a implementar aparecieron dos huecos que ninguna revisión había marcado, los dos escritos con artículo determinado:

- RF-005: *"pasa la validación para **los tres casos de prueba definidos**"*, y CV-05: *"sobre **los tres XML de referencia**"*. Nunca se definen. De ellos dependen T-101, T-104, T-505 y CV-05 entero.
- RF-001: *"un `businessKey` derivado **determinísticamente de la venta**"*. No dice de qué campos. De ahí cuelga ADR-003 completo.

**Por qué importa:** "los tres casos definidos" se lee como una referencia a algo que existe en otra parte del documento, y el ojo la da por resuelta. Un `[PENDIENTE]` explícito habría bloqueado la aprobación; el artículo determinado pasó una revisión técnica entera. Y el coste de descubrirlo tarde no es simétrico: si se hubiera detectado en la fase 5, CV-05 habría dictado retroactivamente qué debía modelar el dominio de la fase 1 — el estándar habría vuelto a ser rehén del último adaptador, que es exactamente lo que ADR-001 existe para impedir. Sobre `businessKey`, la elección por defecto cómoda (hash del contenido) habría hecho que dos pedidos distintos con las mismas líneas el mismo día colapsaran en una factura, y ese fallo solo aparece en producción con un cliente que repite un pedido.

**Regla derivada:** al empezar una fase se recorre el SRS buscando referencias con artículo determinado a artefactos que el documento no define —"los tres casos", "el subconjunto aplicable", "los campos mínimos", "la venta"—, y cada una se resuelve **antes** de escribir código de esa fase, registrando la resolución en `tasks/todo.md`. Un adjetivo como "determinístico" o "canonicalizado" describe una propiedad, no una especificación: hay que fijar los campos y su orden.

**Cómo sabríamos que la regla falló:** un test de una fase tardía obliga a cambiar el modelo de dominio de la fase 1. Indicador temprano: una tarea cuya implementación arranca con una decisión que nadie tomó por escrito, o un criterio de verificación que cita un artefacto sin ruta de archivo ni definición. Aplicar la misma sospecha a lo que queda: "los campos mínimos del RD 1007/2023" (RF-003) y "la huella sobre los campos canonicalizados" (T-400) todavía no están fijados.

---

## L-012 · Un test de propiedades no puede detectar una deriva del modo de redondeo

**Fecha:** 2026-08-15 · **Origen:** hallazgo al ejecutar la prueba de falsabilidad de T-100

**Qué pasó:** T-100 quedó en verde con 27 tests, de los cuales 10 son propiedades jqwik a 1000 casos cada una — 10.000 combinaciones generadas. Aplicando L-004 se cambió `Money.ROUNDING` de `HALF_UP` a `HALF_EVEN` para ver la suite fallar. Falló: `Tests run: 27, Failures: 3`. Pero los tres fallos fueron **tests de ejemplo**. Las diez propiedades pasaron todas: `FiscalArithmeticPropertyTest ... Tests run: 10, Failures: 0`.

**Por qué importa:** el criterio de verificación que el SRS asigna a T-100 es exactamente el que no detecta el fallo — *"test de propiedades jqwik: para cualquier combinación, base + impuesto = total con escala 2"*. Y no es un defecto de los tests escritos, es estructural: asociatividad, conmutatividad, escala 2 y `base + impuesto = total` son **leyes**, y se cumplen bajo `HALF_UP`, `HALF_EVEN`, `HALF_DOWN` y `FLOOR` por igual. El modo de redondeo no es una ley del álgebra, es una **elección normativa**, y una elección solo se fija clavando valores concretos. Con jqwik como único criterio, alguien "limpia" el redondeo a `HALF_EVEN` —que es el default bancario y el de `Math.round` en media docena de librerías— y los 10.000 casos siguen verdes mientras cada factura se desvía un céntimo.

**Regla derivada:** las propiedades cubren las leyes; los ejemplos clavan las decisiones. Toda constante normativa —modo de redondeo, escala, orden de canonicalización, algoritmo de huella— necesita al menos un test de ejemplo con un valor elegido para **discriminar** esa constante de sus alternativas plausibles, y el comentario dice cuál sería el resultado bajo la alternativa. En `MoneyTest`: `0.10 × 0.05 = 0.0050` da `0.01` bajo `HALF_UP` y `0.00` bajo `HALF_EVEN`. Un caso que da lo mismo bajo ambos modos —como `0.095 → 0.10`— no discrimina nada aunque parezca un empate.

Aplica directamente a lo que viene: la huella SHA-256 de T-400/T-401 tiene el mismo perfil. Una propiedad del tipo "recalcular la huella da el mismo valor" se cumple con **cualquier** canonicalización consistente, incluida una equivocada. Hace falta un vector de prueba con el hash literal esperado.

**Cómo sabríamos que la regla falló:** cambiar una constante normativa deja la suite en verde. El check es mecánico y barato: por cada constante de este tipo, alterarla a su alternativa plausible y confirmar que algún test se pone rojo. Indicador temprano: una tarea cuyo único criterio de verificación es un test de propiedades, o un test de ejemplo cuyo valor esperado coincide bajo dos modos de redondeo distintos.

---

## L-013 · Un ejemplo de dos elementos puede cancelarse por simetría sin que el algoritmo esté bien

**Fecha:** 2026-08-15 · **Origen:** hallazgo al aplicar la prueba de falsabilidad de L-012 a T-101

**Qué pasó:** `InvoiceTotals.compute` reparte un descuento de documento entre grupos de IVA (BG-23) de forma proporcional, con el último grupo absorbiendo el residuo de redondeo para que la suma dé exacta. El caso RC-2 (grupos al 7 % y 19 %, descuento 10.00 sobre una base de 110.00) fue el primer test escrito para probarlo, con el resultado correcto (100.00 exacto) verificado a mano. Al desactivar la absorción de residuo como sonda de falsabilidad, **RC-2 no lo detectó**: siguió en verde. La razón es aritmética, no un defecto del test — con dos grupos que se reparten el 100 % (proporciones `p` y `1-p`), si `10·p` redondea hacia arriba entonces `10·(1-p)` redondea hacia abajo por construcción, y las dos derivas se cancelan. RC-2, sin buscarlo, había elegido un caso simétrico.

**Por qué importa:** un test que pasa "por casualidad aritmética" da la misma confianza que uno que prueba algo real, hasta que alguien lo mira con sospecha. Sin la sonda de falsabilidad de L-004, este hueco habría quedado invisible: RC-2 seguiría en la suite, documentado como "la prueba de redondeo de BG-23", sin serlo.

**Regla derivada:** cuando un test ejemplifica un algoritmo de reparto o redondeo, además del caso "realista" del dominio hace falta al menos un caso construido para que la deriva de redondeo **no pueda cancelarse por simetría** — en la práctica, tres o más grupos con la misma proporción nominal (aquí, tres importes netos iguales repartiendo un descuento entre sí), donde cada grupo redondea en la misma dirección. Es la generalización de L-012: no alcanza con un caso de ejemplo, hace falta uno diseñado para que el error se acumule en vez de cancelarse.

**Cómo sabríamos que la regla falló:** una sonda de falsabilidad (L-004) sobre código de reparto/redondeo no encuentra ningún test que se ponga en rojo. Indicador temprano: un test de reparto con exactamente dos grupos o dos categorías — con dos elementos que suman el total, hay una probabilidad real de que cualquier error de redondeo se cancele por aritmética, no porque el código esté bien.
