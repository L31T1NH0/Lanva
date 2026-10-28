# Fixtures de referência

Fonte: https://github.com/T-x-T/oxide
Commit: `8e0770ea9fb13f585134a7aa376682e514cd2ff6` (Oxide 0.9.0).
Licença original: GPL-3.0, preservada em `LICENSE`.

Os arquivos sob `server/` e `lib/` são trechos completos dos arquivos usados pelo patch, sem as alterações do Oxide Mobile. `serialize.rs` é o serializador original, usado na comparação de bytes. `nbt_types.rs` contém apenas as definições dos enums NBT de `basic_types/src/nbt.rs`, equivalentes aos enums usados pelo serializador.

O teste de integração compila os construtores e ticks modificados com estruturas de teste locais. Não compila nem substitui o servidor completo.

O trecho em `data/blocks/get_block_state_from_block_state_id/` preserva os estados 0, 255 e 256 do arquivo original para testar a divisão da tabela nos limites dos grupos. O arquivo `click_container.rs` preserva a implementação original para testar a adaptação ao Rust 1.90.
