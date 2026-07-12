--[[
dynamic_crop_lite.lua

Continuous automatic black-bar crop for mpv that tracks aspect-ratio changes
mid-playback (IMAX / variable-AR content) WITHOUT scraping verbose logs.

It reads cropdetect's results through the vf-metadata property (the clean path
mpv's built-in autocrop.lua uses), so it does NOT call mp.enable_messages, and
produces no console spam.

Dark scenes are handled on two levels:

1. Structural filters: real black bars are symmetric (letterbox is
   vertically centered, pillarbox horizontally centered) and leave at least
   one axis at full size. A lamp in the middle of a dark frame shrinks BOTH
   axes; a bright doorway on the left is OFF-CENTER. Both are rejected
   outright, and only frames that actually match a candidate count toward
   confirming it — so the confirm windows can stay short.

2. Two-poll promotion: a single flickering frame (grain, flash, hard cut)
   can't replace the current candidate and zero its confirmation count; a
   new detection must repeat on the next poll before it's promoted.

Scene-change latency is still asymmetric on top of that: a change that
REVEALS more image applies almost instantly, while a change that HIDES image
waits a bit longer. The very first crop after load gets its own short window,
since "there were never bars before" can't be faked by a scene change.

Requirements:
  - mpv build with the video-crop property (recent mpv).
  - hwdec = no or any -copy variant (cropdetect needs frames in system memory).
  - an ffmpeg build that exports cropdetect metadata (lavfi.cropdetect.*).

NuvioDesktop integration note: this script does not auto-enable and is driven
from native code via the "crop-set" script-message ("yes" to start, "no" to
stop and clear, "keep" to stop but keep the current crop). Its key bindings are
never triggered because the desktop player disables mpv keyboard input.

Options: override in mpv.conf via
  script-opts-append=dynamic_crop_lite-<name>=<value>
--]]
local options = {
    enabled = false, -- native code enables via the "crop-set" script-message
    mode = "black", -- "black": black-pixel detection, cheap and tunable with
    -- one threshold (limit / limit_hdr, alt+k / alt+j). "mvedges": find the
    -- playing video by motion vectors + Canny edges (ffmpeg >= 5.1,
    -- auto-falls back to black). Immune to "bars aren't quite black", but
    -- grain in the bars reads as edges/motion — tune mv_threshold and
    -- mv_low/mv_high up on grainy content — and its per-frame motion
    -- estimation costs real CPU at 4K. Toggle live with alt+m to compare.
    mv_threshold = 8, -- (mvedges) motion threshold in pixel units; raise to
    -- ignore grain "motion" in the bars
    mv_low = 5, -- (mvedges) Canny weak-edge threshold (value/255)
    mv_high = 15, -- (mvedges) Canny strong-edge threshold (value/255); raise
    -- both to ignore grain edges in the bars
    limit = 26, -- (black mode) black threshold (value/255) for SDR content
    limit_hdr = 48, -- (black mode) threshold for HDR (PQ/HLG) content: HDR
    -- encodes often have bars slightly above code black that still LOOK jet
    -- black on screen, so cropdetect needs far more headroom. Tune live with
    -- alt+k (raise) / alt+j (lower) until bars are detected but dark scenes
    -- aren't, then put the winning value here.
    round = 2, -- crop dimensions divisible by this (even)
    min_ratio = 0.5, -- reject crops smaller than this fraction of source
    poll_interval = 0.05, -- seconds between metadata polls (lower = snappier)
    grow_time = 0.1, -- matched seconds to confirm a REVEAL (bars shrinking)
    shrink_time = 0.8, -- matched seconds to confirm a HIDE (bars growing)
    initial_time = 0.25, -- matched seconds to confirm the FIRST crop after load
    jitter = 2, -- px of detection wobble tolerated without resetting the count
    center_tol = 8, -- px a cropped axis may be off-center before rejection
    one_axis_full = true, -- reject crops that shrink BOTH axes (dark-scene filter);
    -- set false only for content that genuinely letterboxes and pillarboxes at once
    start_delay = 1, -- seconds after load before first detection
    debug = false, -- log detections/applies; set false once it works
}
require("mp.options").read_options(options, "dynamic_crop_lite")

local label = mp.get_script_name() .. "-cd"

local st = {
    active = false,
    source = nil,
    applied = nil,
    candidate = nil,
    cand_w = nil,
    cand_h = nil,
    cand_x = nil,
    cand_y = nil,
    cand_hits = 0,
    pend_key = nil,
    pend_w = nil,
    pend_h = nil,
    pend_x = nil,
    pend_y = nil,
    timer = nil,
    paused = false,
    no_meta = 0,
    seen_meta = false,
    limit = nil, -- effective threshold for the current file
    raw_t = 0, -- last time raw detection was logged (debug)
}

local function log(msg)
    if options.debug then
        mp.msg.info(msg)
    end
end

local function round_down(n)
    return math.floor(n / options.round) * options.round
end

local function cropdetect_present()
    for _, f in pairs(mp.get_property_native("vf") or {}) do
        if f.label == label then
            return true
        end
    end
    return false
end

local function pick_limit()
    local gamma = mp.get_property("video-params/gamma", "")
    if gamma == "pq" or gamma == "hlg" then
        return options.limit_hdr
    end
    return options.limit
end

local function insert_cropdetect()
    if cropdetect_present() then
        return
    end
    if options.mode == "mvedges" then
        -- skip=0 is safe to pass here: skip and mode landed in the same
        -- cropdetect rewrite, so any build with mvedges also has skip.
        mp.command(
            string.format(
                "no-osd vf pre @%s:cropdetect=mode=mvedges:mv_threshold=%d:low=%d/255:high=%d/255:round=%d:reset=1:skip=0",
                label,
                options.mv_threshold,
                options.mv_low,
                options.mv_high,
                options.round
            )
        )
        if cropdetect_present() then
            return
        end
        mp.msg.warn("cropdetect mode=mvedges not supported by this ffmpeg build; falling back to black mode.")
        options.mode = "black"
    end
    mp.command(
        string.format(
            "no-osd vf pre @%s:cropdetect=limit=%d/255:round=%d:reset=1",
            label,
            st.limit or options.limit,
            options.round
        )
    )
end

local function remove_cropdetect()
    if cropdetect_present() then
        mp.command(string.format("no-osd vf remove @%s", label))
    end
end

local function set_crop(str)
    mp.set_property("video-crop", str or "")
    st.applied = str
end

local function reset_detection()
    st.candidate = nil
    st.cand_hits = 0
    st.pend_key = nil
end

local function applied_dims()
    local w, h = (st.applied or ""):match("^(%d+)x(%d+)")
    if w then
        return tonumber(w), tonumber(h)
    end
    return st.source.w, st.source.h -- nil or unparsable: treat as uncropped
end

-- Structural plausibility: real bars leave one axis full, and whatever axis
-- IS cropped must be (near-)centered. Dark-scene artifacts rarely manage both.
local function plausible(w, h, x, y, full_w, full_h)
    if options.one_axis_full and not (full_w or full_h) then
        return false
    end
    if not full_w and math.abs(x - (st.source.w - w) / 2) > options.center_tol then
        return false
    end
    if not full_h and math.abs(y - (st.source.h - h) / 2) > options.center_tol then
        return false
    end
    return true
end

-- Are two detections "the same"? A wobble of a few pixels must not count as
-- a change, or noisy sources never converge.
local function same_det(k1, w1, h1, x1, y1, k2, w2, h2, x2, y2)
    if k1 == "full" or k2 == "full" then
        return k1 == k2
    end
    local tol = options.jitter
    return math.abs(w1 - w2) <= tol
        and math.abs(h1 - h2) <= tol
        and math.abs(x1 - x2) <= tol
        and math.abs(y1 - y2) <= tol
end

local function matches_candidate(key, w, h, x, y)
    return st.candidate ~= nil and same_det(key, w, h, x, y, st.candidate, st.cand_w, st.cand_h, st.cand_x, st.cand_y)
end

local function matches_pending(key, w, h, x, y)
    return st.pend_key ~= nil and same_det(key, w, h, x, y, st.pend_key, st.pend_w, st.pend_h, st.pend_x, st.pend_y)
end

local function poll()
    if st.paused or not st.active or not st.source then
        return
    end

    local m = mp.get_property_native("vf-metadata/" .. label)
    local w = m and tonumber(m["lavfi.cropdetect.w"])
    local h = m and tonumber(m["lavfi.cropdetect.h"])
    local x = m and tonumber(m["lavfi.cropdetect.x"])
    local y = m and tonumber(m["lavfi.cropdetect.y"])

    if not (w and h and x and y) or w <= 0 or h <= 0 then
        -- In mvedges mode a static or dark scene legitimately yields no fresh
        -- detection — that's a feature: we hold the current crop through it.
        -- Only warn if metadata has NEVER appeared (build doesn't export it).
        st.no_meta = st.no_meta + 1
        if st.no_meta == 60 and not st.seen_meta then
            mp.msg.warn(
                "No cropdetect metadata via vf-metadata/"
                    .. label
                    .. "; your ffmpeg build does not export it. This approach cannot work here."
            )
        end
        return
    end
    st.no_meta = 0
    st.seen_meta = true

    if options.debug then
        local t = mp.get_time()
        if t - st.raw_t >= 1 then
            st.raw_t = t
            mp.msg.info(string.format("raw: %dx%d+%d+%d (limit %d)", w, h, x, y, st.limit or options.limit))
        end
    end

    if w < st.source.w * options.min_ratio or h < st.source.h * options.min_ratio then
        return -- implausibly small: dark scene / fade — ignore, hold current crop
    end

    local full_w = w >= st.source.w - options.round
    local full_h = h >= st.source.h - options.round

    local key, dw, dh
    if full_w and full_h then
        key, dw, dh = "full", st.source.w, st.source.h
    else
        if not plausible(w, h, x, y, full_w, full_h) then
            return -- off-center or both-axes box: dark-scene artifact — ignore
        end
        key, dw, dh = string.format("%dx%d+%d+%d", w, h, x, y), w, h
    end

    if matches_candidate(key, dw, dh, x, y) then
        st.pend_key = nil
        st.cand_hits = st.cand_hits + 1
    elseif matches_pending(key, dw, dh, x, y) then
        -- Second consecutive poll agreeing on a new detection: promote it.
        st.candidate, st.cand_w, st.cand_h, st.cand_x, st.cand_y = key, dw, dh, x, y
        st.cand_hits = 2
        st.pend_key = nil
        log("Detected: " .. key)
    else
        -- A single differing frame (grain flicker, flash, hard cut) must NOT
        -- dethrone the current candidate and zero its confirmation count.
        -- Park it as pending; it only takes over if the next poll agrees.
        st.pend_key, st.pend_w, st.pend_h, st.pend_x, st.pend_y = key, dw, dh, x, y
        return
    end

    local need
    if not st.applied and key ~= "full" then
        -- First crop after load: bars present from the start can't be a
        -- dark-scene fake-out, so don't sit through the full shrink window.
        need = options.initial_time
    else
        local aw, ah = applied_dims()
        local revealing = (st.cand_w * st.cand_h) >= (aw * ah)
        need = revealing and options.grow_time or options.shrink_time
    end
    -- Only frames that actually matched count — time spent in rejected/dark
    -- frames does not accumulate toward confirmation.
    if st.cand_hits * options.poll_interval < need then
        return
    end

    -- NOTE: must be an explicit if — `cond and nil or x` collapses to x in Lua.
    local target
    if st.candidate ~= "full" then
        target = st.candidate
    end
    if target ~= st.applied then
        set_crop(target)
        log("Applied crop: " .. (target or "full"))
    end
end

local function start()
    st.active = true
    if not st.limit then
        st.limit = pick_limit()
        log(string.format("using limit %d (gamma: %s)", st.limit, mp.get_property("video-params/gamma", "?")))
    end
    reset_detection()
    insert_cropdetect()
    if st.timer then
        st.timer:kill()
    end
    st.timer = mp.add_periodic_timer(options.poll_interval, poll)
end

local function stop(keep_crop)
    st.active = false
    if st.timer then
        st.timer:kill()
        st.timer = nil
    end
    remove_cropdetect()
    if not keep_crop then
        set_crop(nil)
    end
    reset_detection()
end

local function on_start()
    stop(false)
    st.applied = nil
    st.source = nil
    st.no_meta = 0
    st.seen_meta = false
    st.limit = nil -- re-pick per file (SDR vs HDR)

    if mp.get_property_native("current-tracks/video/image") ~= false then
        return
    end
    if mp.get_property("video-crop") == nil then
        mp.msg.error("This mpv build has no video-crop property; script disabled.")
        return
    end

    local w = mp.get_property_number("width")
    local h = mp.get_property_number("height")
    if not (w and h) then
        return
    end
    st.source = { w = round_down(w), h = round_down(h) }

    local hw = mp.get_property("hwdec-current", "no")
    if hw ~= "no" and not hw:find("-copy") and hw ~= "crystalhd" and hw ~= "rkmpp" then
        mp.msg.warn("hwdec '" .. hw .. "' is not copy-back; cropdetect may fail.")
    end

    if options.enabled then
        mp.add_timeout(options.start_delay, start)
    end
end

mp.observe_property("pause", "bool", function(_, v)
    st.paused = v
end)

mp.register_event("seek", reset_detection)

local function adjust_limit(delta)
    st.limit = math.max(0, math.min(255, (st.limit or pick_limit()) + delta))
    if st.active then
        remove_cropdetect()
        insert_cropdetect()
        reset_detection()
    end
    mp.osd_message(string.format("Auto-crop limit: %d", st.limit))
end

mp.add_key_binding("alt+k", "crop_limit_up", function()
    adjust_limit(8)
end, { repeatable = true })

mp.add_key_binding("alt+j", "crop_limit_down", function()
    adjust_limit(-8)
end, { repeatable = true })

mp.add_key_binding("alt+m", "crop_mode_toggle", function()
    options.mode = (options.mode == "black") and "mvedges" or "black"
    if st.active then
        remove_cropdetect()
        insert_cropdetect()
        reset_detection()
    end
    -- insert_cropdetect may fall back to black if mvedges is unsupported
    mp.osd_message("Auto-crop mode: " .. options.mode)
end)

mp.add_key_binding("k", "toggle_crop", function()
    if st.active then
        stop(true)
        mp.osd_message("Auto-crop: paused (crop kept)")
    else
        start()
        mp.osd_message("Auto-crop: on")
    end
end)

-- Native (NuvioDesktop) control entry point. The desktop player disables mpv
-- keyboard input, so enabling/disabling is driven from Kotlin through this
-- script-message instead of the "k" binding:
--   crop-set yes   -> begin detecting
--   crop-set no    -> stop detecting and clear the crop (restore full frame)
--   crop-set keep  -> stop detecting but leave the current crop applied
mp.register_script_message("crop-set", function(value)
    if value == "yes" then
        if not st.active then
            start()
        end
    elseif value == "keep" then
        if st.active then
            stop(true)
        end
    else -- "no" or anything else: fully disable
        stop(false)
    end
end)

mp.register_event("end-file", function()
    stop(false)
end)
mp.register_event("file-loaded", on_start)
