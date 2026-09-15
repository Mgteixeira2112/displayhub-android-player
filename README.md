# DisplayHub Android Player

Cliente Android/Android TV oficial do DisplayHub.

## V1

- ativação por token de instalação já existente no DisplayHub;
- identidade persistente do dispositivo;
- WebView em tela cheia;
- tela sempre ligada;
- recuperação automática após perda de conexão;
- início no boot quando permitido pelo Android/TV Box;
- heartbeat reaproveitando `poll_registered_device_assignment`;
- comandos remotos com confirmação no Supabase;
- geração de APK de teste pelo GitHub Actions.

## Comandos remotos já tratados

- `reload_displays` — recarrega a WebView;
- `restart_player` — reinicia a Activity do player;
- `enter_kiosk` — tenta Lock Task e usa modo imersivo como fallback;
- `exit_kiosk` — sai do Lock Task/modo imersivo;
- `reboot_device` — reinicia o aparelho quando o app for Device Owner; caso contrário retorna falha explícita.

O Android reutiliza a infraestrutura de `player_devices`, `player_device_commands`, `device_installation_requests` e os RPCs já existentes no Supabase do DisplayHub. Nenhuma base separada foi criada.

## Configuração de build

A URL padrão já aponta para o projeto Supabase oficial do DisplayHub. Para um APK operacional é necessário fornecer a chave anônima pública como propriedade Gradle:

`DISPLAYHUB_SUPABASE_ANON_KEY`

No GitHub Actions, configure um Repository Secret com esse mesmo nome. O CI compila mesmo sem a chave, mas esse APK não conseguirá ativar nem consultar o backend.

O painel web continua no repositório `Mgteixeira2112/displayhub`. Este repositório contém apenas o player Android.
