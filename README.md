# 🎼 ScoreCast

**ScoreCast** é um leitor e apresentador de partituras musicais em PDF para Android, desenvolvido especialmente para músicos, regentes e bandas que utilizam uma **segunda tela** (monitores portáteis USB-C, TVs via cabo HDMI ou telas sem fio/Cast) como estante digital durante ensaios e apresentações ao vivo.

Enquanto a partitura é projetada em alta resolução na tela externa, a tela do smartphone funciona como uma **mesa de controle**, permitindo buscar músicas, organizar a setlist e virar as páginas com agilidade e precisão.

---

## ✨ Funcionalidades Principais

- 📺 **Projeção em Display Externo (Multi-Display):**
  - Utiliza a **Android Presentation API** para criar uma janela física dedicada e independente no monitor externo.
  - A projeção permanece estável e sem piscar (*flicker-free*) mesmo se o celular for girado ou o tema for alternado.
- 🔄 **Orientação Inteligente para Monitores Horizontais:**
  - Detecta automaticamente monitores conectados em formato paisagem (*landscape*) e gira a partitura em 90° para aproveitar 100% da altura da tela na vertical.
  - Opção para **inverter a rotação** (-90° / 90°) nas configurações, adaptando-se a qualquer estante ou montagem física do monitor.
- 📖 **Efeitos de Transição de Página:**
  - **Virar página (3D Curl):** Efeito realista de dobra de página com curvatura física 3D e iluminação via Jetpack Compose (`pagecurl`).
  - **Deslizar (Slide):** Deslocamento horizontal fluido com curva suave de aceleração Bézier cúbica.
  - **Nenhuma (Instantânea):** Troca imediata de página.
- 🦶 **Controle por Pedais Bluetooth e Botões de Volume:**
  - Suporte nativo a pedais de virar página (*page turners* Bluetooth) via teclas padrão (`Page Up/Down`, `D-Pad`, `Media Next/Prev`).
  - Suporte opcional para virar páginas usando as teclas físicas de volume do smartphone.
- 🔍 **Otimização de Espaço e Zoom:**
  - **Crop de Margens (0 a 20%):** Remove as bordas brancas do papel e re-renderiza o PDF em resolução maior, maximizando as notas musicais na tela externa.
  - **Zoom Rápido (1 a 10%):** Botão de escala instantânea acelerada por GPU para focar em compassos específicos.
- 📁 **Gerenciamento de Repertório e Set List:**
  - Seleção de pasta de partituras via *Storage Access Framework* (SAF) com permissões persistentes.
  - Abas **Todos** e **Set list** (favoritos salvos localmente).
  - Busca textual em tempo real.
  - Ordenação por nome (A-Z / Z-A) ou data de modificação.
- 🎨 **Interface Moderna (Estilo One UI):**
  - Tema Claro e **Tema Escuro puro para telas AMOLED** (`#000000`).
  - Efeito de vidro líquido (*Liquid Glassmorphism*) com shaders reais de GPU no diálogo de configurações.
  - Suporte completo a **Edge-to-Edge** (Android 15+).
  - Internacionalização nativa em **Português** e **Inglês**.

---

## 🛠️ Tecnologias e Bibliotecas

- **Linguagem:** [Kotlin](https://kotlinlang.org/)
- **SDK:** Min SDK 33 (Android 13+), Target SDK 36, Compile SDK 36
- **UI:** Híbrido Android Views + [Jetpack Compose](https://developer.android.com/jetpack/compose) (BOM 2026.01.00)
- **Renderização de PDF:** `android.graphics.pdf.PdfRenderer` com cache LRU sincronizado e proteção contra vazamento de memória
- **Efeitos Visuais:**
  - [PageCurl](https://github.com/oleksandrbalan/pagecurl) – Animação 3D de virar página
  - [Liquid](https://github.com/fletchmckee/liquid) – Shaders de vidro líquido e refração em GPU (`RuntimeShader`)
- **Arquitetura de Apresentação:** `android.app.Presentation` com ciclo de vida customizado (`PresentationLifecycleOwner`)

---

## 🚀 Como Executar o Projeto

### Pré-requisitos
- [Android Studio](https://developer.android.com/studio) (versão recente com suporte a Java 17/21 e Kotlin)
- Dispositivo Android 13+ (físico ou emulador)
  - *Dica:* Para testar apresentações no emulador, ative um display secundário em **Settings > Developer Options > Simulate secondary displays**.

### Clonar e Compilar
```bash
# Clonar o repositório
git clone https://github.com/samysglauber/scorecast.git
cd scorecast

# Abrir no Android Studio ou compilar via terminal:
./gradlew assembleDebug
```

---

## 📄 Licença

Este projeto está sob a licença [MIT](LICENSE).
