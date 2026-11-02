# Aviso de Radar — app Android

App que roda em segundo plano (com a tela apagada) e apita e fala "Radar à frente, 400 metros, limite 60"
quando você se aproxima de um radar do seu arquivo CSV. Usa a mesma lógica da página de teste e do aparelho.

## Como gerar o app (sem saber programar)

1. No computador, instale o **Android Studio** (gratuito): https://developer.android.com/studio
2. Abra o Android Studio → **Open** → escolha esta pasta (`radar-android`).
3. Espere o "Gradle sync" terminar (barra no rodapé; na primeira vez baixa coisas e demora uns minutos, precisa de internet).
   Se pedir para instalar o "Android SDK 34", aceite.
4. No celular: **Configurações → Sobre o telefone** → toque 7 vezes em "Número da versão" →
   volte em **Opções do desenvolvedor** → ligue **Depuração USB**.
5. Ligue o celular ao computador por cabo USB, aceite o aviso no celular e clique no botão verde ▶ (**Run**) no Android Studio.
   O app é instalado e abre.

Alternativa para ter um arquivo APK: menu **Build → Build Bundle(s) / APK(s) → Build APK(s)**. O arquivo fica em
`app/build/outputs/apk/debug/app-debug.apk`; copie para o celular e abra (permita "instalar apps desconhecidos").

## Como usar

1. Copie o CSV do MapaRadar para o celular (Downloads, por exemplo).
2. Abra o app → **Escolher arquivo CSV** → selecione o arquivo.
3. Marque os tipos de radar que quer (padrão: fixo, semáforo com radar e móvel).
4. Toque em **Iniciar aviso** e aceite as permissões (localização e notificações).
5. Apareça uma notificação "Aviso de Radar ligado": pode bloquear a tela ou abrir o Maps. Para desligar, **Parar** no app ou na notificação.

## Para não ser pausado pelo Android

- Localização: permita "Permitir o tempo todo" ou "Durante o uso do app".
- Bateria: toque em **Evitar que o Android pause o app** e escolha "Sem restrições" para o Aviso de Radar.
  Em Xiaomi, Samsung, Motorola etc. há também "início automático"/"economia de bateria" nas configurações do aparelho.
- Deixe o celular carregando no carro: o GPS gasta bateria.
- O som toca no volume de mídia do celular (ou no Bluetooth do carro). Ajuste o volume antes.

## Ajustes

- Alcance do alerta: 15 segundos de distância (mínimo 300 m). Edite em `RadarCore.kt`, função `alertRadius`.
- Abaixo de 10 km/h o aviso fica desligado (`MIN_SPEED_KMH`).
- A voz usa o sintetizador do celular em português; se não falar, instale "Voz do Google" em português nas configurações de texto para fala.

## Honestidade sobre o teste

- A lógica de cálculo é a mesma da página web que testei com o seu CSV (5 simulações, todas alertaram a ~410 m).
- Este projeto Android **não foi compilado nem executado** por mim: o ambiente em que eu trabalho não tem o Android SDK nem acesso aos
  servidores do Google. Se o Android Studio mostrar algum erro vermelho na primeira vez, me mande o texto ou uma foto que eu corrijo.
- Não testado: GPS real, som no alto-falante, comportamento em cada marca de celular.

## Licença dos dados

Os dados do MapaRadar são de uso pessoal (CC BY-NC-ND). Não inclua o CSV no app nem o distribua.
O aviso não substitui a sinalização da via nem a atenção ao dirigir.
