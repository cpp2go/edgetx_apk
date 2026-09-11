-- TNS|Lua Abs 480x320|TNE
-- Absolute-position test for Horus-class 480x320 layouts on an 800x480 LCD.
-- Open: Radio -> Tools -> "Lua Abs 480x320"
--
-- If firmware parses like native EdgeTX:
--   * The cyan frame and all content stay in the TOP-LEFT 480x320 area.
--   * Outside that box (right of x=480, below y=320) stays empty after clear.
--   * LCD_W/LCD_H are reported (expect 800x480 on Android/TX16 compat).
-- Do NOT use LCD_W/LCD_H for the 480x320 drawing region.

local PAGE_COUNT = 3
local page = 1

-- Hardcoded design size (never use LCD_W/LCD_H for these)
local AW = 480
local AH = 320

local function clampPage()
  if page < 1 then page = PAGE_COUNT end
  if page > PAGE_COUNT then page = 1 end
end

-- Outside the 480x320 box: hatch so empty area is obvious after upscale.
local function paintOutside()
  lcd.drawFilledRectangle(AW, 0, LCD_W - AW, LCD_H, COLOR_THEME_PRIMARY1)
  lcd.drawFilledRectangle(0, AH, AW, LCD_H - AH, COLOR_THEME_PRIMARY1)
  lcd.drawText(AW + 8, 8, "OUTSIDE 480x320", COLOR_THEME_PRIMARY2 + SMLSIZE)
  lcd.drawText(AW + 8, 28, "(must stay empty of script UI)", COLOR_THEME_PRIMARY2 + SMLSIZE)
  lcd.drawText(8, AH + 8, "BELOW 320", COLOR_THEME_PRIMARY2 + SMLSIZE)
end

local function paintFrame()
  -- Design rectangle fill
  lcd.drawFilledRectangle(0, 0, AW, AH, COLOR_THEME_SECONDARY3)
  -- Outer border of absolute 480x320
  lcd.drawRectangle(0, 0, AW, AH, COLOR_THEME_FOCUS)
  lcd.drawRectangle(1, 1, AW - 2, AH - 2, COLOR_THEME_FOCUS)
end

local function drawPageBox()
  paintOutside()
  paintFrame()

  -- Corners of absolute box
  lcd.drawFilledRectangle(0, 0, 40, 40, COLOR_THEME_FOCUS)
  lcd.drawText(6, 10, "TL", COLOR_THEME_PRIMARY2 + BOLD)

  lcd.drawFilledRectangle(AW - 40, 0, 40, 40, COLOR_THEME_ACTIVE)
  lcd.drawText(AW - 34, 10, "TR", COLOR_THEME_PRIMARY2 + BOLD)

  lcd.drawFilledRectangle(0, AH - 40, 40, 40, COLOR_THEME_FOCUS)
  lcd.drawText(6, AH - 30, "BL", COLOR_THEME_PRIMARY2 + BOLD)

  lcd.drawFilledRectangle(AW - 40, AH - 40, 40, 40, COLOR_THEME_ACTIVE)
  lcd.drawText(AW - 34, AH - 30, "BR", COLOR_THEME_PRIMARY2 + BOLD)

  -- Center cross inside box only
  lcd.drawLine(AW / 2, 40, AW / 2, AH - 40, SOLID, COLOR_THEME_FOCUS)
  lcd.drawLine(40, AH / 2, AW - 40, AH / 2, SOLID, COLOR_THEME_FOCUS)

  lcd.drawText(AW / 2, AH / 2 - 22, "480 x 320 BOX",
               COLOR_THEME_PRIMARY1 + BOLD + CENTER)
  lcd.drawText(AW / 2, AH / 2 - 2, "absolute pixels",
               COLOR_THEME_PRIMARY1 + CENTER)
  lcd.drawText(AW / 2, AH / 2 + 18,
               string.format("host LCD=%dx%d", LCD_W, LCD_H),
               COLOR_THEME_PRIMARY1 + SMLSIZE + CENTER)

  -- Top bar like Xuan-HUD (hardcoded width 480)
  lcd.drawFilledRectangle(0, 0, 480, 36, lcd.RGB(30, 30, 120))
  lcd.drawText(8, 8, "1 Absolute box", COLOR_THEME_PRIMARY2 + BOLD)
  lcd.drawText(AW - 8, 8, string.format("P%d/%d", page, PAGE_COUNT),
               COLOR_THEME_PRIMARY2 + BOLD + RIGHT)
end

local function drawPageMarks()
  paintOutside()
  paintFrame()

  lcd.drawFilledRectangle(0, 0, 480, 36, lcd.RGB(30, 30, 120))
  lcd.drawText(8, 8, "2 Edge markers", COLOR_THEME_PRIMARY2 + BOLD)
  lcd.drawText(AW - 8, 8, string.format("P%d/%d", page, PAGE_COUNT),
               COLOR_THEME_PRIMARY2 + BOLD + RIGHT)

  -- Markers at exact absolute edges
  local marks = {
    {0, 40, "(0,40)"},
    {240, 40, "(240,40)"},
    {479, 40, "(479,40)"},
    {0, 160, "(0,160)"},
    {240, 160, "(240,160)"},
    {400, 160, "(400,160)"},
    {0, 300, "(0,300)"},
    {240, 300, "(240,300)"},
    {400, 300, "(400,300)"},
  }
  for _, m in ipairs(marks) do
    lcd.drawFilledRectangle(m[1], m[2], 4, 4, COLOR_THEME_FOCUS)
    lcd.drawText(m[1] + 6, m[2] - 2, m[3], SMLSIZE + COLOR_THEME_PRIMARY1)
  end

  lcd.drawText(8, AH - 20, "Markers must stay inside cyan frame",
               SMLSIZE + COLOR_THEME_PRIMARY1)
end

local function drawPageInfo()
  paintOutside()
  paintFrame()

  lcd.drawFilledRectangle(0, 0, 480, 36, lcd.RGB(30, 30, 120))
  lcd.drawText(8, 8, "3 Diagnostics", COLOR_THEME_PRIMARY2 + BOLD)
  lcd.drawText(AW - 8, 8, string.format("P%d/%d", page, PAGE_COUNT),
               COLOR_THEME_PRIMARY2 + BOLD + RIGHT)

  local y = 48
  local function line(s)
    lcd.drawText(12, y, s, 0)
    y = y + 22
  end

  line(string.format("Design box : %d x %d (hardcoded)", AW, AH))
  line(string.format("LCD_W      : %d", LCD_W))
  line(string.format("LCD_H      : %d", LCD_H))
  line(string.format("Expect LCD : 800 x 480 on TX16/Android"))
  line("")
  line("PASS if UI only in top-left box")
  line("FAIL if cyan area stretches full screen")
  line("")
  line("PG- / PG+ : switch page")
  line("Long RTN  : exit")

  local tw, th = lcd.sizeText("Qg")
  line(string.format("sizeText('Qg') = %d x %d", tw, th))
end

local function paint()
  lcd.clear(COLOR_THEME_PRIMARY1)
  if page == 1 then
    drawPageBox()
  elseif page == 2 then
    drawPageMarks()
  else
    drawPageInfo()
  end
end

local function init()
  page = 1
end

local function run(event)
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
