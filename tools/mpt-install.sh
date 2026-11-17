#!/data/data/com.termux/files/usr/bin/bash
# Установка MoneyPrinterTurbo в Termux — внутри отдельной Ubuntu (proot-distro).
# Твой Telegram-бот в обычном Termux не затрагивается: у MoneyPrinterTurbo своя система и свой Python.
set -e

echo "=== 1/4 Ставлю proot-distro и Ubuntu (бот не трогаю) ==="
pkg update -y
pkg install -y proot-distro tmux
if ! proot-distro list 2>/dev/null | grep -A2 -i "ubuntu" | grep -qi "installed"; then
  proot-distro install ubuntu || true
fi

echo
echo "=== 2/4 Ключи ==="
read -r -p "Вставь ключ Gemini (AIza...): " GEMINI_KEY
read -r -p "Вставь ключ Pexels (pexels.com/api): " PEXELS_KEY

echo
echo "=== 3/4 Ставлю MoneyPrinterTurbo внутри Ubuntu (15-30 минут, ~2 ГБ) ==="
proot-distro login ubuntu --env GEMINI_KEY="$GEMINI_KEY" --env PEXELS_KEY="$PEXELS_KEY" -- bash -s <<'INNER'
set -e
export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y git curl ca-certificates ffmpeg
if ! command -v uv >/dev/null 2>&1; then
  curl -LsSf https://astral.sh/uv/install.sh | sh
fi
export PATH="$HOME/.local/bin:$PATH"
cd ~
[ -d MoneyPrinterTurbo ] || git clone --depth 1 https://github.com/harry0703/MoneyPrinterTurbo.git
cd MoneyPrinterTurbo
uv sync --frozen
[ -f config.toml ] || cp config.example.toml config.toml
sed -i "s|^llm_provider = .*|llm_provider = \"gemini\"|" config.toml
sed -i "s|^gemini_api_key = .*|gemini_api_key = \"$GEMINI_KEY\"|" config.toml
sed -i "s|^gemini_model_name = .*|gemini_model_name = \"gemini-flash-latest\"|" config.toml
sed -i "s|^pexels_api_keys = .*|pexels_api_keys = [\"$PEXELS_KEY\"]|" config.toml
sed -i "s|^subtitle_provider = .*|subtitle_provider = \"edge\"|" config.toml
echo "MoneyPrinterTurbo установлен."
INNER

echo
echo "=== 4/4 Делаю команду запуска ==="
cat > "$HOME/mpt" <<'RUN'
#!/data/data/com.termux/files/usr/bin/bash
termux-wake-lock 2>/dev/null || true
echo "Открой в браузере: http://127.0.0.1:8501  (остановить: Ctrl+C)"
proot-distro login ubuntu -- bash -lc 'export PATH="$HOME/.local/bin:$PATH"; cd ~/MoneyPrinterTurbo && sh webui.sh'
RUN
chmod +x "$HOME/mpt"

echo
echo "ГОТОВО. Запуск: открой новую сессию Termux и введи:  ~/mpt"
echo "Потом в браузере телефона: http://127.0.0.1:8501"
