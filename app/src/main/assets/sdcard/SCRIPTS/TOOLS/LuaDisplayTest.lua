-- TNS|Lua Display Test|TNE
-- Standalone TOOLS script (legacy lcd API, no useLvgl).
-- Open: Radio -> Tools -> "Lua Display Test"
-- Long RTN exits (handled by firmware); return 1 also exits.

local PAGE_COUNT = 4
local page = 1
local menuPos = 5
local lineStep = 25
local col1 = 15
local FOOTER_H = 28
local CONTENT_BOTTOM

local function clampPage()
  if page < 1 then page = PAGE_COUNT end
  if page > PAGE_COUNT then page = 1 end
end

local function header(title)
  lcd.drawFilledRectangle(0, 0, LCD_W, 30, COLOR_THEME_SECONDARY1)
  lcd.drawText(8, 5, title, COLOR_THEME_PRIMARY2 + BOLD)
  lcd.drawText(LCD_W - 8, 5, string.format("P%d/%d", page, PAGE_COUNT),
               COLOR_THEME_PRIMARY2 + BOLD + RIGHT)
end

local function footer(msg)
  lcd.drawFilledRectangle(0, LCD_H - FOOTER_H, LCD_W, FOOTER_H, COLOR_THEME_SECONDARY3)
  lcd.drawText(8, LCD_H - FOOTER_H + 4, msg, COLOR_THEME_SECONDARY1)
end

local function drawPageGrid()
  header("1 Grid and corners")

  lcd.drawFilledRectangle(0, 0, 36, 36, COLOR_THEME_FOCUS)
  lcd.drawText(6, 8, "TL", COLOR_THEME_PRIMARY2 + BOLD)

  lcd.drawFilledRectangle(LCD_W - 36, 0, 36, 36, COLOR_THEME_ACTIVE)
  lcd.drawText(LCD_W - 30, 8, "TR", COLOR_THEME_PRIMARY2 + BOLD)

  lcd.drawFilledRectangle(0, CONTENT_BOTTOM - 36, 36, 36, COLOR_THEME_FOCUS)
  lcd.drawText(6, CONTENT_BOTTOM - 28, "BL", COLOR_THEME_PRIMARY2 + BOLD)

  lcd.drawFilledRectangle(LCD_W - 36, CONTENT_BOTTOM - 36, 36, 36, COLOR_THEME_ACTIVE)
  lcd.drawText(LCD_W - 30, CONTENT_BOTTOM - 28, "BR", COLOR_THEME_PRIMARY2 + BOLD)

  local stepX = math.floor(LCD_W / 10)
  local stepY = math.floor((CONTENT_BOTTOM - 30) / 10)
  for x = 0, LCD_W - 1, stepX do
    lcd.drawLine(x, 30, x, CONTENT_BOTTOM, DOTTED, COLOR_THEME_SECONDARY2)
  end
  for y = 30, CONTENT_BOTTOM, stepY do
    lcd.drawLine(0, y, LCD_W - 1, y, DOTTED, COLOR_THEME_SECONDARY2)
  end

  lcd.drawLine(LCD_W / 2, 30, LCD_W / 2, CONTENT_BOTTOM, SOLID, COLOR_THEME_FOCUS)
  lcd.drawLine(0, (CONTENT_BOTTOM + 30) / 2, LCD_W - 1, (CONTENT_BOTTOM + 30) / 2,
               SOLID, COLOR_THEME_FOCUS)

  lcd.drawText(LCD_W / 2, LCD_H / 2 - 10, "CENTER",
               COLOR_THEME_PRIMARY1 + BOLD + CENTER)
  lcd.drawText(LCD_W / 2, LCD_H / 2 + 8,
               string.format("%dx%d", LCD_W, LCD_H),
               COLOR_THEME_PRIMARY1 + CENTER)

  footer("Corners on edges; grid even")
end

local function drawPageLines()
  header("2 Line spacing")

  local y0 = 34
  for i = 1, 10 do
    local y = y0 + (i - 1) * lineStep
    local attr = (menuPos == i) and INVERS or 0
    lcd.drawText(col1, y, string.format("%2d) Menu line %d", i, i), attr)
  end

  footer(string.format("step=%d px; line %d inverted", lineStep, menuPos))
end

local function drawPageText()
  header("3 Text integrity")

  local y = 36
  lcd.drawText(8, y, "ABCDEFGHIJKLMNOPQRSTUVWXYZ", 0)
  y = y + lineStep
  lcd.drawText(8, y, "abcdefghijklmnopqrstuvwxyz", 0)
  y = y + lineStep
  lcd.drawText(8, y, "0123456789  !@#$%^&*()", 0)
  y = y + lineStep
  lcd.drawText(8, y, "Press [Enter] to start", COLOR_THEME_FOCUS)
  y = y + lineStep + 4
  lcd.drawText(8, y, "Qg", 0)
  local tw, th = lcd.sizeText("Qg")
  lcd.drawText(8, y + lineStep, string.format("sizeText('Qg') = %d x %d", tw, th), 0)
  y = y + lineStep * 2
  lcd.drawFilledRectangle(8, y, LCD_W - 16, 40, COLOR_THEME_SECONDARY3)
  lcd.drawText(LCD_W / 2, y + 12, "Button label", COLOR_THEME_FOCUS + CENTER)

  footer("Glyphs complete; button centered")
end

local function drawPageInfo()
  header("4 Diagnostics")

  local y = 40
  local function line(s)
    lcd.drawText(12, y, s, 0)
    y = y + lineStep
  end

  line(string.format("LCD_W = %d", LCD_W))
  line(string.format("LCD_H = %d", LCD_H))
  line(string.format("LCD_W==800 : %s", (LCD_W == 800) and "yes" or "no"))

  local tw, th = lcd.sizeText("Qg")
  line(string.format("sizeText STD: %d x %d px", tw, th))

  line("")
  line("PG- / PG+ : switch page")
  line("UP/DN on page 2: move highlight")
  line("Long RTN : exit script")

  footer("Android expect: 800x480")
end

local function paint()
  lcd.clear()
  if page == 1 then
    drawPageGrid()
  elseif page == 2 then
    drawPageLines()
  elseif page == 3 then
    drawPageText()
  else
    drawPageInfo()
  end
end

local function init()
  page = 1
  menuPos = 5
  CONTENT_BOTTOM = LCD_H - FOOTER_H
  if LCD_H <= 64 then
    lineStep = 9
    col1 = 0
  else
    lineStep = 25
    col1 = 15
  end
end

local function run(event)
  if page == 2 then
    if event == EVT_VIRTUAL_NEXT then
      menuPos = math.min(10, menuPos + 1)
    elseif event == EVT_VIRTUAL_PREV then
      menuPos = math.max(1, menuPos - 1)
    end
  end

  if event == EVT_VIRTUAL_NEXT_PAGE then
    page = page + 1
    clampPage()
  elseif event == EVT_VIRTUAL_PREV_PAGE then
    page = page - 1
    clampPage()
  end

  paint()
  return 0
end

return { init=init, run=run }
