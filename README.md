# Lanva 0.3.2

Lanva é o novo nome do projeto anteriormente chamado Oxide Mobile/Fabric Mobile, mantido na pasta `oxide-mobile`. O identificador Android `io.oxide.mobile` e os caminhos privados permanecem compatíveis com as instalações existentes.

App Android ARM64 para executar um servidor Minecraft Java com Fabric. O usuário escolhe a versão estável, e o app instala o launcher oficial em uma pasta separada para cada versão. Nenhuma versão recebe mods automaticamente. Geyser, Floodgate e Fabric API não são incluídos. Bedrock fica para uma etapa posterior.

## No celular

Instale `app/build/outputs/apk/release/app-release.apk` como atualização, sem desinstalar nem limpar os dados. A assinatura local usa a chave de teste anterior. Android 8+ (API 26), ARM64.

1. O app consulta o catálogo oficial ao abrir e usa a última versão estável como padrão. A lista aparece da mais nova para a mais antiga, com comparação numérica (1.21.10 antes de 1.21.9). Uma escolha manual é mantida; **Usar última estável** reativa o padrão automático. Sem internet, fica disponível o último catálogo salvo e a versão conhecida anteriormente.
2. Escolha a versão. Cada versão tem seu próprio mundo, mods e configurações.
3. Toque em **Iniciar servidor** e aceite a EULA do Minecraft. A primeira instalação precisa de internet para baixar Fabric, Minecraft e bibliotecas.
4. Conecte o Minecraft Java da mesma versão ao IP mostrado, porta `25565/TCP`. No próprio celular, use `127.0.0.1:25565`.
5. Use **Salvar e parar** antes de alterar arquivos. O app só confirma salvamento após a mensagem de todas as dimensões salvas e a saída normal do servidor.

A configuração inicial usa sobrevivência, dificuldade normal, estruturas, até 8 jogadores e visão/simulação de 4 chunks. Autenticação Java online. O heap tem limite de 1024 MB; o processo usa memória nativa adicional. Configurações existentes não são reescritas a cada início.

## Versões e Java

O catálogo mostra versões estáveis compatíveis com os runtimes incluídos:

| Minecraft | Runtime automático |
| --- | --- |
| 1.18 até 1.20.4 | Java 17 |
| 1.20.5 até 1.21.x | Java 21 |
| 26.x | Java 25 |

Versões anteriores a 1.18 e snapshots não são oferecidos. Novas famílias de versões precisam de revisão dos requisitos de Java antes de entrar no catálogo. Os requisitos Java e a versão JNA são conferidos nos metadados Mojang. O Fabric Loader e o instalador estáveis são selecionados na primeira instalação; `installation.json` registra as versões escolhidas e evita atualização silenciosa nas próximas execuções.

Os runtimes vêm do Zalith Launcher 2.7, com checksum fixado e avisos legais preservados. Três runtimes aumentam o tamanho do APK, mas permitem usar Java adequado sem exigir outro app ou executar bibliotecas baixadas no armazenamento gravável.

## Arquivos e mundos

**Pasta dos servidores** permite escolher uma subpasta de `/storage/emulated/0/`, digitando um caminho como `Lanva` ou `Documents/Minecraft`, ou usando o seletor do Android. Uma pasta por versão fica dentro da pasta escolhida: `/storage/emulated/0/Lanva/26.3/`. A raiz do armazenamento e as pastas `Android` não são aceitas.

O acesso direto do Minecraft à pasta pública exige a permissão de gerenciamento de arquivos no Android 11+, ou acesso ao armazenamento no Android 8–10. O app explica e abre a tela de permissão antes da mudança. A permissão do seletor de documentos, sozinha, não fornece os caminhos de arquivo usados pela JVM. Java, bootstrap e links de bibliotecas nativas continuam privados, pois o armazenamento compartilhado não suporta esses links/execução.

Ao trocar a pasta, **Copiar servidores atuais** copia as versões com o servidor parado e só altera a configuração após concluir a cópia. Os originais são mantidos; pastas de versões existentes no destino não são sobrescritas nem mescladas. **Usar sem copiar** abre os arquivos existentes no destino ou prepara novos servidores. Se houver falha ou permissão revogada, o app não muda silenciosamente para outra pasta. O Oxide e o Fabric antigos em `files/server/` e `files/fabric-server/` não são migrados por essa cópia.

**Arquivos do servidor** permite navegar nas pastas, editar arquivos de texto (até 1 MB), criar arquivos/pastas, importar arquivos (até 256 MB), exportar e excluir arquivos individuais. Segure um arquivo para exportar ou excluir. Importar/exportar usa o seletor de documentos do Android. Edição e importação ficam bloqueadas durante preparação, execução e parada do servidor. Exportar durante execução não garante uma cópia consistente de arquivos que o servidor está alterando; pare antes de copiar dados de mundo.

- Instalações privadas: `files/servers/<versão>/`; públicas: `<pasta escolhida>/<versão>/`.
- Mundo atual: `<pasta da versão>/world/`.
- Fabric anterior: `files/fabric-server/`, acessível em **Outras pastas → Pastas antigas do app**.
- Oxide anterior: `files/server/`, também acessível em **Outras pastas → Pastas antigas do app**.
- Java compartilhado entre instalações: `files/runtimes/java<major>-zalith2.7-v2/`.

Os mundos anteriores não são movidos, convertidos ou apagados. Trocar de versão cria/abre outra instalação; não faz downgrade automático do mesmo mundo. O navegador transfere arquivos individuais; a troca de pasta copia as instalações completas. Não há backup automático. Desinstalar ou limpar os dados apaga os arquivos privados; pastas públicas fora de Android permanecem, mas a preferência da pasta e a permissão precisam ser configuradas novamente.

Para instalar mods, importe jars compatíveis para a pasta `mods` da versão escolhida. Fabric API deve ser instalado quando um mod exigir; não é necessário para a base do servidor.

## Estrutura

- `app/`: interface Android, serviço em primeiro plano, catálogo Fabric, preparação e controle do processo, navegador de arquivos, seleção de pasta pública e cópia dos servidores.
- `native/runtime/launcher.c`: processo nativo pequeno que cria a JVM via JNI e acompanha a vida do app.
- `native/runtime/jvm.c`: carrega bibliotecas e preserva a localização do runtime para HotSpot.
- `native/runtime/FabricBootstrap.java`: inicia o launcher Fabric sem modificá-lo.
- `scripts/build-fabric.py`: prepara os runtimes, JNA Android e launcher C. Servidores são baixados pelo app conforme a versão escolhida.
- `native/mcopt/`, `native/mcopt-server/` e scripts Oxide: referência histórica, fora do build padrão.

A correção de **Failed setting boot class path** cobre os dois caminhos de localização: o runtime Android usa diretamente `dladdr`; HotSpot no Linux também passa por `realpath`. Ambos precisam ver `JAVA_HOME/lib/server/libjvm.so`, mesmo com a biblioteca física extraída na pasta nativa do APK. A localização de `libawt.so` também é preservada em `dladdr` e `realpath`, pois AWT carrega `libawt_headless.so` a partir dessa pasta. As ligações simbólicas são atualizadas após cada instalação do APK. A versão anterior interceptava somente `realpath`, insuficiente para esse runtime Android.

## Compilar e verificar

Ferramentas: Python 3.12+, JDK 17 para Gradle/Android, JDK 25 para a verificação host, SDK 35, build-tools 35.0.0 e NDK 28.2.13676358.

Mods já existentes na pasta do servidor são preservados. Para remover o mcopt de uma instalação anterior, pare o servidor e exclua seu JAR da pasta `mods`; o app não o reinstala.

```bash
export ANDROID_HOME=/caminho/android-sdk
export JAVA_HOME=/caminho/jdk17
export FABRIC_BUILD_JAVA_HOME=/caminho/jdk25
python3 scripts/build-fabric.py
./scripts/test.sh
python3 tests/check-fabric-runtime.py
./gradlew --no-daemon assembleRelease lintRelease
```

O teste de runtime reproduz o caminho Android com uma biblioteca de teste e comprova a falha sem a correção de `dladdr`. Também inicia uma JVM host real com `libjvm` e AWT relocadas, verifica o bootstrap e faz encode/decode PNG com `BufferedImage`. A regressão de AWT reproduz o erro original sem o mapeamento corrigido. Quando há servidor Fabric e bibliotecas em cache, verifica o Minecraft real em modo ajuda, sem mundo/EULA. A mesma verificação de JVM e bootstrap passou nos hosts Java 17, 21 e 25. Esse teste não substitui execução no Android.

Para assinar com a chave de teste existente:

```bash
apksigner sign --ks "$HOME/.android/debug.keystore" --ks-key-alias androiddebugkey \
  --ks-pass pass:android --key-pass pass:android \
  --out app/build/outputs/apk/release/app-release.apk \
  app/build/outputs/apk/release/app-release-unsigned.apk
```

## Validação e limites atuais

A correção de AWT e a remoção da instalação automática do mcopt passaram nos testes gerais e na regressão de imagem PNG. O teste host também iniciou o Fabric/Minecraft real em modo ajuda. `assembleRelease` e `lintRelease` passaram nesta sessão. A execução do Geyser com o APK corrigido ainda precisa ser validada no Android.

Testes locais cobrem o ciclo do processo, salvamento, erros, runtimes por versão, isolamento dos mundos, gravação atômica, limites de texto e bloqueio de caminhos externos, ordenação numérica das versões, isolamento do runtime privado, migração sem sobrescrever mundos e limpeza após cópias incompletas. O APK local 0.3.1 foi compilado com as ferramentas do SDK e teve assinatura verificada. Gradle/lint e ADB não puderam executar nesta sessão devido ao bloqueio de sockets do ambiente.

A versão 0.3.0 iniciou no celular conforme relato do usuário. Ainda falta testar as mudanças da 0.3.1 no aparelho, incluindo permissão de arquivos, seleção/cópia da pasta pública, persistência e ordenação/padrão do catálogo. A correção da JVM tem teste de regressão em host e a inicialização Android foi confirmada pelo usuário na 0.3.0. O pacote contém JNA 4.4.0 a 5.18.0; uma versão futura que exija JNA ausente pede atualização do APK. Os runtimes fornecidos têm bibliotecas alinhadas a 4 KB, portanto suporte a aparelhos com páginas de 16 KB não está validado.

O serviço e o wake lock não impedem encerramento forçado, falta de memória ou políticas do fabricante. Não há backup automático nem gerenciamento de versões dos mods.

## Fontes e licenças

- [Zalith Launcher 2.7](https://github.com/ZalithLauncher/ZalithLauncher2/releases/tag/2.7): runtimes e bibliotecas JNA Android por versão; licenças OpenJDK preservadas nos zips e na instalação.
- [Fabric Meta](https://github.com/FabricMC/fabric-meta): catálogo e geração do launcher oficial.
- [JNA](https://github.com/java-native-access/jna): biblioteca Android necessária para as dependências nativas do Minecraft.
- Fontes históricas mcopt: Apache-2.0, atribuição a Noah Dunnagan em `native/mcopt-server/LICENSE` e `NOTICE`.
