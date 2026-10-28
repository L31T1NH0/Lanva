# mcopt no servidor Oxide

O build copia estes módulos para `lib/src/mcopt/` no checkout isolado do Oxide e aplica os pontos de integração. O aplicativo não passa a depender de Fabric, Sodium, FFM ou de outro checkout local.

- `vendor/noise.rs`: kernel portátil original do mcopt com uma API Rust segura para alturas. A ABI C original é mantida para executar as fixtures de referência.
- `terrain.rs`: colinas de pedra opcionais no Overworld do mundo de teste. Sem biomas, cavernas ou equivalência à geração vanilla. Defina `OXIDE_MCOPT_SEED` antes da primeira criação do mundo; a seed é persistida em `world/mcopt-terrain-v1.seed`. Reinícios usam essa seed mesmo sem a variável. Mudanças de seed e ativação sobre mundos existentes são rejeitadas.
- `nbt.rs`: serialização de todos os tipos NBT do Oxide em um único buffer; o buffer é reutilizado entre chunks no salvamento de regiões.
- `region.rs`: comparação em streaming do NBT existente antes de comprimir/gravar um chunk. Só ignora conteúdo exatamente igual, inclusive `LastUpdate`. Regiões e regiões de entidades são substituídas por arquivo temporário, sync e rename.
- random ticks: o patch prepara uma tabela dos estados que o próprio Oxide processa, antes da primeira amostragem. Mantém o número de sorteios e evita criar posições e chamar o dispatcher para blocos inertes. Não muda a velocidade de ticks.

Os kernels e hashes de referência vieram de `/home/leite/Documents/GitHub/mcopt-linux`. Preserve `vendor/LICENSE` e `vendor/NOTICE`. As adaptações Java foram reimplementadas para as estruturas Rust do Oxide. Arquivos derivados de Sodium e código de renderização não são incorporados.

Execute `./scripts/test.sh`: testa a igualdade de bytes com o serializador original do Oxide, os hashes de ruído registrados pelo mcopt, a persistência da seed, comparação de conteúdo e substituição de arquivos. O servidor completo e o APK ainda exigem as dependências e testes reais descritos no README principal. Não há ganho de desempenho medido no Android.

O kernel de ruído também foi compilado nesta sessão para Android ARM64 com o NDK local, e os segmentos ELF foram conferidos com alinhamento de 16 KB. Isso valida a compilação do kernel, sem provar sua execução no aparelho ou a compilação completa do Oxide.
