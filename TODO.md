- Implementar cacheo del classpath
- Optimizar indexado

STARTUP PROCESS:
- Llamar a gradle (obtener classpath y sourcesets)
- Carga todos los archivos kotlin en RAM
- Parsea y compila todos los archivos kotlin, actualizando el index
- Textdocument lintall
