# DisplayHub Android Player — contrato para agentes de código

Este arquivo vale para agentes de desenvolvimento que trabalham neste repositório. Ele não faz parte do runtime do APK.

## Escopo

Repositório Android/TV: `Mgteixeira2112/displayhub-android-player`.

O painel Web vive em `Mgteixeira2112/displayhub`. Não misture commits dos dois projetos.

## Fluxo obrigatório

1. Inspecione antes de editar.
2. Faça `git fetch --prune origin`.
3. Prove o SHA atual de `origin/main`.
4. Nunca edite a checkout principal. Use worktree isolada.
5. Crie branch pequena a partir da `main` atual.
6. Faça somente as mudanças necessárias.
7. Rode build/testes.
8. Push sem force.
9. Abra PR.
10. Confirme CI no SHA exato da PR.
11. Pare no gate humano.

## Proibições

- Nunca faça push direto na `main`.
- Nunca faça merge.
- Nunca use force-push.
- Nunca publique APK/release.
- Nunca altere signing secrets/keystore.
- Nunca altere Supabase de produção.
- Nunca use service-role.
- Nunca execute comandos em TVs reais.
- Nunca incremente versão/release sem a tarefa pedir explicitamente.

## Build mínimo

Use Java 17 e Gradle 8.9, alinhados à CI oficial.

```bash
gradle :app:assembleDebug
```

Se a tarefa tocar atualização OTA, assinatura ou release, prepare a alteração em PR e marque a publicação como bloqueada por gate humano.

## Hardware

CI verde não prova comportamento físico de Android TV.

Quando a correção depender do ATV real, finalize com:
`VALIDAÇÃO FÍSICA PENDENTE`.

## Relação com o Web

Mudanças que também exigirem painel/backend devem gerar PR separado em `Mgteixeira2112/displayhub`.

## Gate final

Ao terminar, reporte:
- branch;
- SHA;
- arquivos alterados;
- build/testes;
- PR;
- CI;
- validação física pendente, se houver.

Então pare. Não faça merge nem release.
