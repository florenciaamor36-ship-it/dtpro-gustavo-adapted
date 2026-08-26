# La Clave Argentina - Cliente SSH & VPN TUN para Android

Cliente Android propio de **La Clave Argentina**, enfocado exclusivamente en conexiones **SSH** y la administración de una red privada **VPN mediante TUN**.

El proyecto utiliza **Apache MINA SSHD** (v2.19.0) como motor principal de encriptación y transporte SSH, con soporte estricto de seguridad, verificación de huellas digitales de servidor y forwarding TCP bidireccional.

---

## 🚀 Funciones SSH Disponibles

1. **SSH Directo (TCP)**: Conexión SSH TCP directa al servidor y puerto SSH.
2. **SSH sobre HTTP Payload**: Inyección de cabeceras HTTP personalizadas con soporte de transporte de bytes y bloques separados por `[split]`.
3. **SSH mediante Proxy HTTP**: Conexión a través de proxy HTTP con validación del código de respuesta.
4. **SSH mediante TLS / SNI**: Túnel seguro SSL/TLS con nombre de host SNI y validación estándar de certificados X.509 en Android.
5. **SSH sobre WebSocket (HTTP / WSS)**: Encapsulado de tráfico SSH sobre canal WebSocket cuando es seleccionado por el usuario.
6. **Autenticación Múltiple**: Soporte para autenticación por contraseña, llave privada (PEM/OpenSSH RSA/Ed25519) con passphrase opcional e interacción Keyboard-Interactive.
7. **Verificación Estricta de Host Key**: Almacenamiento seguro de huellas de servidor (`HostKeyRepository`). Rechazo automático ante cambios de huella (prevención Man-In-The-Middle) o claves no autorizadas.
8. **Forwarding TCP Bidireccional**: Redirección de puertos SOCKS5 local (1080) y proxy HTTP transparente (8080).
9. **VPN TUN**: Interfaz TUN `VpnService` con enrutamiento de tráfico completo y servidores DNS configurables.
10. **Diagnóstico Seguro**: Consola de registros que enmascara contraseñas y secretos automáticamente.

---

## 🛠 Transportes e Inyección de Payload

El motor de Payload procesa exactamente la sintaxis preservando espacios, mayúsculas, minúsculas y saltos de línea.

### Reemplazo de Tokens

- `[crlf]`: Salto de línea CRLF (`\r\n`).
- `[lf]`: Salto de línea LF (`\n`).
- `[cr]`: Retorno de carro CR (`\r`).
- `[split]`: Frontera de división de bloques de envío.
- `[ua]`: User-Agent.
- `[host]`: Host del servidor SSH o frontal.
- `[port]`: Puerto del servidor SSH.
- `[host_port]`: Combinación `host:port`.
- `[method]`: Método HTTP (GET, CONNECT, POST, etc.).
- `[protocol]`: Protocolo HTTP (`HTTP/1.1` o `HTTPS`).

### Ejemplo de Payload

```text
CONNECT [host_port] [protocol][crlf]Host: [host][crlf]User-Agent: [ua][crlf]Connection: Keep-Alive[crlf][crlf]
```

### Reglas de Respuesta HTTP
- **101 / 200 / 20x**: Respuesta válida. El transporte se establece y se inicia la autenticación SSH.
- **403 / 4xx / 5xx**: Rechazo inmediato. Se detiene el envío de bloques posteriores y NO se inicia SSH.
- **Incompleto / Timeout / Cierre Prematuro**: Detención inmediata del socket y reporte de error.

---

## 🔒 Seguridad y Host Keys

Por políticas estrictas de seguridad:
- **NUNCA** se utiliza `StrictHostKeyChecking=no` ni la aceptación automática deshabilitada.
- Las huellas digitales desconocidas deben ser confirmadas o aprobadas explícitamente.
- Si una huella digital almacenada cambia, la conexión se **rechaza inmediatamente** por sospecha de intercepción.
- No se marcan conexiones como "Conectadas" únicamente por haber autenticado SSH; se valida la creación de canales y el tráfico bidireccional real.

---

## 🚦 Estados de la Conexión

1. **Desconectado**: Túnel detenido y todos los sockets/recursos liberados.
2. **Conectando**: Apertura del socket TCP/TLS, envío de Payload o conexión al proxy.
3. **Autenticando**: Verificación de huella digital de servidor y autenticación SSH (password/llave privada).
4. **Transporte Establecido**: Socket persistente validado.
5. **Forwarding Activo**: Canales SSH y puertos SOCKS5/HTTP iniciados en `127.0.0.1`.
6. **Conectado / VPN Activa**: Interfaz TUN enrutando tráfico bidireccional confirmado.
7. **Reconectando**: Reintento automático de conexión tras pérdida temporal de señal.
8. **Error**: Conexión o transporte rechazado con mensaje descriptivo en consola.

---

## 🌐 DNS y Limitaciones de UDP

- **DNS**: Configuración de servidores DNS primario y secundario personalizados (ejemplo por defecto: `8.8.8.8` y `8.8.4.4`).
- **UDP**: Soporte de tráfico UDP sujeto a la presencia real de un servidor de retransmisión UDP en el extremo remoto. No se simula ni falsifica tráfico UDP sobre TCP.

---

## 📋 Requisitos para VPN TUN

- Android 7.0 (API 24) o superior.
- Permiso de Android `VpnService` concedido por el usuario.
- Servicio en primer plano (Foreground Service) activo con notificación de estado.

---

## 🧪 Procedimiento de Prueba en Laboratorio

Para probar en entorno de laboratorio local de prueba:

1. Iniciar un servidor SSH de laboratorio en un host autorizado.
2. Configurar perfil en la aplicación con datos de prueba ficticios:
   - **Nombre**: `Perfil Demo Lab`
   - **Host SSH**: `ssh.example.test`
   - **Puerto SSH**: `22`
   - **Usuario SSH**: `demo_user`
   - **Contraseña SSH**: `demo_password`
   - **Host Frontal / Proxy**: `proxy.example.test` (puerto `8080`)
   - **SNI**: `front.example.test`
3. Presionar **CONECTAR**.
4. Verificar en la consola de diagnóstico la secuencia completa:
   - Conexión al socket
   - Validación de respuesta HTTP
   - Verificación de huella SSH
   - Autenticación exitosa
   - Establecimiento de interfaz TUN VPN.

> **Advertencia de Seguridad**: No se incluyen ni deben subirse credenciales reales ni claves privadas de producción al repositorio.

---

© 2026 La Clave Argentina. Todos los derechos reservados.
