> Documento histórico do Oxide/Geyser. A arquitetura atual está no README.md; o build padrão usa Java + Fabric sem Bedrock.

# Oxide + Geyser Standalone no Android

## Ideia

Criar um aplicativo Android capaz de iniciar um servidor local de Minecraft diretamente no celular.

O servidor principal será o **Oxide**, escrito em Rust. Para permitir conexões do Minecraft Bedrock, o aplicativo também executará o **Geyser Standalone** como um processo separado.

## Arquitetura

```text
Minecraft Bedrock
       |
       | UDP 19132
       v
Geyser Standalone
       |
       | protocolo Java
       v
Oxide
TCP 25565
       |
       v
Mundo Minecraft
```

Jogadores Java não precisam passar pelo Geyser:

```text
Minecraft Java
       |
       | TCP 25565
       v
Oxide
```

## No celular

O aplicativo executará os dois componentes:

```text
Android
|
|-- Oxide
|   |-- porta 25565
|   `-- mundo
|
`-- Geyser Standalone
    |-- porta 19132
    `-- encaminha para 127.0.0.1:25565
```

Ao tocar em **Iniciar servidor**, o aplicativo deverá:

1. iniciar o Oxide;
2. aguardar a porta `25565` ficar disponível;
3. iniciar o Geyser Standalone;
4. configurar o Geyser para encaminhar conexões para `127.0.0.1:25565`;
5. mostrar o IP local e as portas para conexão.

## Autenticação inicial

O primeiro protótipo não usará Floodgate.

O Geyser será configurado inicialmente com autenticação offline, adequada para testes em uma rede local privada.

```text
Bedrock -> Geyser -> Oxide
                 auth offline
```

O suporte a Floodgate poderá ser estudado posteriormente, pois o Oxide atualmente não possui integração própria com ele.

## MVP

A primeira versão precisa apenas provar que:

- o Oxide consegue rodar no Android;
- o Geyser Standalone consegue rodar no mesmo aparelho;
- um cliente Java consegue entrar diretamente no Oxide;
- um cliente Bedrock consegue entrar pelo Geyser;
- ambos conseguem jogar no mesmo mundo pela rede local.

O objetivo inicial é manter a arquitetura simples e validar a comunicação antes de adicionar gerenciamento de mundos, console, plugins, backups ou outras funções.
