local loadCandidates = {
    "/SCRIPTS/Tetris.lua",
    "./SCRIPTS/Tetris.lua",
    "SCRIPTS/Tetris.lua",
}

local game = nil

local function loadRuntimeScript()
    for i = 1, #loadCandidates do
        local ok, script = pcall(dofile, loadCandidates[i])
        if ok and type(script) == "table" and type(script.run) == "function" then
            return script
        end
    end
    return nil
end

local function init(w, h)
    game = loadRuntimeScript()
    if game and type(game.init) == "function" then
        game.init(w or LCD_W, h or LCD_H)
    end
end

local function run(event, touch)
    if not game then
        game = loadRuntimeScript()
        if game and type(game.init) == "function" then
            game.init(LCD_W, LCD_H)
        end
    end

    if game and type(game.run) == "function" then
        return game.run(event, touch)
    end

    lcd.clear()
    lcd.drawText(8, 8, "Tetris.lua not found", BLINK)
    lcd.drawText(8, 24, "Expect: ./SCRIPTS/Tetris.lua")
    return 0
end

return { init = init, run = run }
