/*
 * Melodify TV helper.
 *
 * Android WebView on TV boxes forwards D-pad keys to the page, but Chromium
 * does not provide spatial focus navigation for ordinary DOM content. This
 * helper adds:
 *   - a predictable directional focus ring between interactive elements,
 *   - very visible focus styles (also applied inline as a CSP fallback),
 *   - automatic soft-keyboard requests when a text field is focused,
 *   - sensible IME hints for phone/OTP fields,
 *   - Enter handling for non-native clickable elements.
 *
 * It is intentionally written in ES5-style JavaScript so it also parses on the
 * older Chromium builds shipped with some NetBox firmware.
 */
(function () {
    'use strict';

    if (window.__melodifyTvEnhancer && window.__melodifyTvEnhancer.refresh) {
        window.__melodifyTvEnhancer.refresh();
        return;
    }

    var SELECTOR = [
        'a[href]',
        'area[href]',
        'button',
        'input:not([type="hidden"])',
        'select',
        'textarea',
        '[tabindex]',
        '[role="button"]',
        '[role="link"]',
        '[role="tab"]',
        '[role="menuitem"]',
        '[role="checkbox"]',
        '[role="radio"]',
        '[role="switch"]',
        '[contenteditable="true"]',
        '[onclick]',
        '[class*="cursor-pointer"]',
        /* Melodify uses this accent-coloured div for text actions such as
           "edit phone number" without a cursor-pointer class. */
        '[class*="text-yellow"]'
    ].join(',');

    var FOCUS_COLOR = '#1ED760';
    var FOCUS_SHADOW = 'rgba(30, 215, 96, 0.35)';
    var STYLE_ID = 'melodify-tv-focus-style';
    var NON_SEMANTIC = { DIV: 1, SPAN: 1, LI: 1, P: 1, SVG: 1, IMG: 1, svg: 1, path: 1 };

    var bridge = null;
    try {
        bridge = window.MelodifyTV || null;
    } catch (e) {
        bridge = null;
    }

    var focusedElement = null;
    var cache = [];
    var cacheAt = 0;
    var enhanceTimer = null;
    var readyTimer = null;
    var readySent = false;
    var readyAttempts = 0;
    var observer = null;
    var lastKeyboardRequest = 0;

    function bridgeCall(name) {
        try {
            if (bridge && typeof bridge[name] === 'function') {
                bridge[name]();
            }
        } catch (e) {
            /* bridge unavailable: navigation still works without the IME */
        }
    }

    // ------------------------------------------------------------------
    // Styling
    // ------------------------------------------------------------------

    function injectStyle() {
        if (document.getElementById(STYLE_ID)) {
            return;
        }
        var style = document.createElement('style');
        style.id = STYLE_ID;
        style.type = 'text/css';
        style.appendChild(document.createTextNode([
            ':focus {',
            '  outline: 3px solid ' + FOCUS_COLOR + ' !important;',
            '  outline-offset: -3px !important;',
            '  box-shadow: 0 0 0 3px ' + FOCUS_SHADOW + ' !important;',
            '}',
            'input:focus, textarea:focus {',
            '  caret-color: ' + FOCUS_COLOR + ' !important;',
            '}',
            'html { scrollbar-width: none !important; }',
            '::-webkit-scrollbar { width: 0 !important; height: 0 !important; }',
            'img { -webkit-user-drag: none !important; }',
            /* The login flow was designed for a tall desktop viewport. On a
               960x540 TV viewport its 124px/100px top spacers push the phone
               field off-screen, so compact only those pages. */
            'html.tv-compact-login [class*="!mt-[124px]"] { margin-top: 32px !important; }',
            'html.tv-compact-login [class*="h-[124px]"] { height: 76px !important; }',
            'html.tv-compact-login [class*="mt-[100px]"] { margin-top: 24px !important; }',
            'html.tv-compact-login [class*="mt-[30px]"] { margin-top: 16px !important; }',
            'html.tv-compact-login [class*="mt-[24px]"] { margin-top: 14px !important; }',
            'html.tv-compact-login [class*="mt-[32px]"] { margin-top: 12px !important; }'
        ].join('\n')));
        (document.head || document.documentElement).appendChild(style);
    }

    function snapshotStyle(el) {
        if (el.__melodifyTvStyle) {
            return;
        }
        el.__melodifyTvStyle = {
            outline: el.style.getPropertyValue('outline'),
            outlinePriority: el.style.getPropertyPriority('outline'),
            outlineOffset: el.style.getPropertyValue('outline-offset'),
            outlineOffsetPriority: el.style.getPropertyPriority('outline-offset'),
            boxShadow: el.style.getPropertyValue('box-shadow'),
            boxShadowPriority: el.style.getPropertyPriority('box-shadow')
        };
    }

    function restoreProperty(el, name, value, priority) {
        if (value) {
            el.style.setProperty(name, value, priority || '');
        } else {
            el.style.removeProperty(name);
        }
    }

    function markFocused(el) {
        if (!el || el === focusedElement || !el.style) {
            return;
        }
        if (focusedElement) {
            unmarkFocused(focusedElement);
        }
        focusedElement = el;
        snapshotStyle(el);
        el.style.setProperty('outline', '3px solid ' + FOCUS_COLOR, 'important');
        el.style.setProperty('outline-offset', '-3px', 'important');
        el.style.setProperty('box-shadow', '0 0 0 3px ' + FOCUS_SHADOW, 'important');
        el.setAttribute('data-tv-focused', 'true');
    }

    function unmarkFocused(el) {
        if (!el) {
            return;
        }
        var saved = el.__melodifyTvStyle;
        if (saved) {
            restoreProperty(el, 'outline', saved.outline, saved.outlinePriority);
            restoreProperty(el, 'outline-offset', saved.outlineOffset, saved.outlineOffsetPriority);
            restoreProperty(el, 'box-shadow', saved.boxShadow, saved.boxShadowPriority);
            delete el.__melodifyTvStyle;
        }
        el.removeAttribute('data-tv-focused');
        if (focusedElement === el) {
            focusedElement = null;
        }
    }

    function scrollIntoViewSmart(el, force) {
        if (!el || !el.getBoundingClientRect) {
            return;
        }
        var rect = el.getBoundingClientRect();
        var viewportH = window.innerHeight || document.documentElement.clientHeight;
        var viewportW = window.innerWidth || document.documentElement.clientWidth;
        if (!force && rect.top >= 8 && rect.left >= 0 && rect.bottom <= viewportH - 8
                && rect.right <= viewportW) {
            return;
        }
        try {
            el.scrollIntoView({ block: 'center', inline: 'nearest', behavior: 'auto' });
        } catch (e) {
            try {
                el.scrollIntoView();
            } catch (ignored) {
                /* nothing else we can do */
            }
        }
    }

    // ------------------------------------------------------------------
    // Candidate discovery
    // ------------------------------------------------------------------

    function isTextField(el) {
        if (!el || !el.tagName) {
            return false;
        }
        var tag = el.tagName.toLowerCase();
        if (tag === 'textarea') {
            return true;
        }
        if (el.isContentEditable) {
            return true;
        }
        if (tag !== 'input') {
            return false;
        }
        var type = (el.getAttribute('type') || 'text').toLowerCase();
        return type !== 'button' && type !== 'submit' && type !== 'reset'
                && type !== 'checkbox' && type !== 'radio' && type !== 'range'
                && type !== 'file' && type !== 'color' && type !== 'image';
    }

    function isNaturalControl(el) {
        var tag = el.tagName ? el.tagName.toLowerCase() : '';
        return tag === 'a' || tag === 'area' || tag === 'button' || tag === 'input'
                || tag === 'select' || tag === 'textarea' || tag === 'summary';
    }

    function hasFocusableAncestor(el) {
        var parent = el.parentElement;
        while (parent && parent !== document.body) {
            if (parent.matches && parent.matches(SELECTOR)) {
                return true;
            }
            parent = parent.parentElement;
        }
        return false;
    }

    function isUsable(el) {
        if (!el || el.nodeType !== 1) {
            return false;
        }
        if (el.disabled) {
            return false;
        }
        if (el.getAttribute('aria-hidden') === 'true') {
            return false;
        }
        if (el.getAttribute('tabindex') === '-1') {
            return false;
        }
        if (el.hasAttribute('inert')) {
            return false;
        }
        if (!el.getClientRects().length) {
            return false;
        }
        var style;
        try {
            style = window.getComputedStyle(el);
        } catch (e) {
            style = null;
        }
        if (style && (style.display === 'none' || style.visibility === 'hidden'
                || style.opacity === '0')) {
            return false;
        }
        if (NON_SEMANTIC[el.tagName] && hasFocusableAncestor(el)) {
            return false;
        }
        var rect = el.getBoundingClientRect();
        if (rect.width < 2 || rect.height < 2) {
            return false;
        }
        return true;
    }

    function ensureTabIndex(el) {
        if (el.hasAttribute('tabindex') || isNaturalControl(el)) {
            return;
        }
        if (NON_SEMANTIC[el.tagName] && hasFocusableAncestor(el)) {
            return;
        }
        el.setAttribute('tabindex', '0');
    }

    function collect(force) {
        var now = Date.now();
        if (!force && cache.length && (now - cacheAt) < 250) {
            return cache;
        }
        var nodes = document.querySelectorAll(SELECTOR);
        var result = [];
        for (var i = 0; i < nodes.length; i++) {
            var el = nodes[i];
            if (!isUsable(el)) {
                continue;
            }
            ensureTabIndex(el);
            result.push(el);
        }
        cache = result;
        cacheAt = now;
        return result;
    }

    function centerOf(el) {
        var rect = el.getBoundingClientRect();
        return {
            x: rect.left + rect.width / 2,
            y: rect.top + rect.height / 2
        };
    }

    function focusElement(el) {
        if (!el) {
            return;
        }
        try {
            el.focus({ preventScroll: true });
        } catch (e) {
            try {
                el.focus();
            } catch (ignored) {
                return;
            }
        }
        if (document.activeElement !== el) {
            // Some SVG/span elements refuse focus; walk up to something usable.
            var parent = el.parentElement;
            while (parent && parent !== document.body) {
                if (parent.hasAttribute('tabindex') || isNaturalControl(parent)) {
                    try {
                        parent.focus();
                    } catch (ignored) {
                    }
                    el = parent;
                    break;
                }
                parent = parent.parentElement;
            }
        }
        markFocused(el);
        scrollIntoViewSmart(el);
    }

    function moveFocus(direction) {
        var list = collect(false);
        if (!list.length) {
            return false;
        }

        var active = document.activeElement;
        var current = null;
        for (var i = 0; i < list.length; i++) {
            if (list[i] === active || (list[i].contains && list[i].contains(active))) {
                current = list[i];
                break;
            }
        }

        if (!current) {
            // Nothing is focused yet. Enter the UI at the element nearest the
            // middle of the screen; using the arrow's direction here makes the
            // first press feel random on layouts whose main action sits just
            // above or below the exact centre line.
            var nearest = null;
            var nearestDistance = Infinity;
            var centerX = (window.innerWidth || document.documentElement.clientWidth) / 2;
            var centerY = (window.innerHeight || document.documentElement.clientHeight) / 2;
            for (var n = 0; n < list.length; n++) {
                var point = centerOf(list[n]);
                var ddx = point.x - centerX;
                var ddy = point.y - centerY;
                var distance = Math.sqrt(ddx * ddx + ddy * ddy);
                if (distance < nearestDistance) {
                    nearestDistance = distance;
                    nearest = list[n];
                }
            }
            if (nearest) {
                focusElement(nearest);
                return true;
            }
            return false;
        }

        var baseX = centerOf(current).x;
        var baseY = centerOf(current).y;

        var best = null;
        var bestScore = Infinity;
        for (var j = 0; j < list.length; j++) {
            var candidate = list[j];
            if (candidate === current) {
                continue;
            }
            if (current && (current.contains(candidate) || candidate.contains(current))) {
                continue;
            }
            var center = centerOf(candidate);
            var dx = center.x - baseX;
            var dy = center.y - baseY;
            var primary;
            var secondary;

            if (direction === 'left') {
                if (dx > -6) continue;
                primary = -dx;
                secondary = Math.abs(dy);
            } else if (direction === 'right') {
                if (dx < 6) continue;
                primary = dx;
                secondary = Math.abs(dy);
            } else if (direction === 'up') {
                if (dy > -6) continue;
                primary = -dy;
                secondary = Math.abs(dx);
            } else {
                if (dy < 6) continue;
                primary = dy;
                secondary = Math.abs(dx);
            }

            // Keep movement predictable: a small sideways offset is fine,
            // a diagonal jump across the screen is not.
            var score = primary + secondary * 2.2;
            if (score < bestScore) {
                bestScore = score;
                best = candidate;
            }
        }

        if (!best) {
            return false;
        }
        focusElement(best);
        return true;
    }

    // ------------------------------------------------------------------
    // Inputs, IME hints and Enter-to-submit
    // ------------------------------------------------------------------

    function tuneInput(el) {
        if (!el || !el.getAttribute) {
            return;
        }
        var type = (el.getAttribute('type') || 'text').toLowerCase();
        var hints = [
            el.getAttribute('name') || '',
            el.getAttribute('id') || '',
            el.getAttribute('placeholder') || '',
            el.getAttribute('aria-label') || ''
        ].join(' ');

        if (type === 'tel' || /phone|mobile|شماره|موبایل|تلفن/i.test(hints)) {
            el.setAttribute('inputmode', 'tel');
            el.setAttribute('autocomplete', 'tel');
            el.setAttribute('enterkeyhint', 'go');
        } else if (type === 'email') {
            el.setAttribute('inputmode', 'email');
            el.setAttribute('enterkeyhint', 'go');
        } else if (type === 'number' || /code|otp|verify|رمز|کد|تاییدیه/i.test(hints)) {
            el.setAttribute('inputmode', 'numeric');
            el.setAttribute('autocomplete', 'one-time-code');
            el.setAttribute('enterkeyhint', 'done');
        } else {
            el.setAttribute('enterkeyhint', 'go');
        }

        el.setAttribute('autocapitalize', 'off');
        el.setAttribute('autocorrect', 'off');
        el.setAttribute('spellcheck', 'false');
    }

    function requestKeyboard() {
        var now = Date.now();
        if (now - lastKeyboardRequest < 500) {
            return;
        }
        lastKeyboardRequest = now;
        bridgeCall('showKeyboard');
    }

    function tuneAllInputs() {
        var inputs = document.querySelectorAll('input, textarea');
        for (var i = 0; i < inputs.length; i++) {
            tuneInput(inputs[i]);
        }
    }

    function isNativeActivatable(el) {
        if (!el || !el.tagName) {
            return false;
        }
        var tag = el.tagName.toLowerCase();
        if (tag === 'button' || tag === 'summary') {
            return true;
        }
        if (tag === 'a' && el.hasAttribute('href')) {
            return true;
        }
        if (tag === 'input') {
            var type = (el.getAttribute('type') || 'text').toLowerCase();
            return type === 'submit' || type === 'button' || type === 'reset'
                    || type === 'checkbox' || type === 'radio' || type === 'image';
        }
        return false;
    }

    function activate(el) {
        if (!el || el === document.body) {
            return false;
        }
        var tag = el.tagName ? el.tagName.toLowerCase() : '';
        if (tag === 'select' || isNativeActivatable(el)) {
            return false; // let the WebView perform its native action
        }
        try {
            el.click();
        } catch (e) {
            return false;
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Key handling
    // ------------------------------------------------------------------

    function onKeyDown(event) {
        if (event.defaultPrevented || event.isComposing) {
            return;
        }

        var key = event.key || '';
        var code = event.keyCode || event.which || 0;
        var active = document.activeElement;
        var tag = active && active.tagName ? active.tagName.toLowerCase() : '';
        var isText = isTextField(active);
        var direction = null;

        if (key === 'ArrowLeft' || key === 'Left' || code === 37 || code === 21) {
            direction = 'left';
        } else if (key === 'ArrowRight' || key === 'Right' || code === 39 || code === 22) {
            direction = 'right';
        } else if (key === 'ArrowUp' || key === 'Up' || code === 38 || code === 19) {
            direction = 'up';
        } else if (key === 'ArrowDown' || key === 'Down' || code === 40 || code === 20) {
            direction = 'down';
        }

        if (direction) {
            if (tag === 'select' || (tag === 'input' && active.type === 'range')) {
                return; // native option picking wins
            }
            if (isText) {
                // Left/Right move the caret; Up/Down leave the field so the
                // remote is never trapped inside an input once the IME hides.
                if (direction === 'left' || direction === 'right' || tag === 'textarea') {
                    return;
                }
            }
            if (moveFocus(direction)) {
                event.preventDefault();
                event.stopPropagation();
            }
            return;
        }

        var isEnter = key === 'Enter' || code === 13 || code === 23 || code === 66;
        var isSpace = key === ' ' || key === 'Spacebar' || code === 32;

        if (isEnter || isSpace) {
            if (!active || active === document.body) {
                var list = collect(false);
                if (list.length) {
                    focusElement(list[0]);
                    event.preventDefault();
                    event.stopPropagation();
                }
                return;
            }

            if (isText) {
                // The site handles Enter itself (Formik onSubmit / OTP
                // onKeyPress). Do not synthesise a second click here.
                return;
            }

            if (tag === 'select') {
                return;
            }

            if (tag === 'input' && (active.type === 'checkbox' || active.type === 'radio')) {
                if (isEnter) {
                    try {
                        active.click();
                    } catch (e) {
                    }
                    event.preventDefault();
                    event.stopPropagation();
                }
                return;
            }

            if (activate(active)) {
                event.preventDefault();
                event.stopPropagation();
            }
            return;
        }

        if (key === 'Escape' || code === 27) {
            bridgeCall('hideKeyboard');
        }
    }

    // ------------------------------------------------------------------
    // Focus lifecycle and SPA changes
    // ------------------------------------------------------------------

    function onFocusIn(event) {
        var el = event.target;
        if (!el || el.nodeType !== 1 || el === document.body
                || el === document.documentElement) {
            return;
        }
        markFocused(el);
        if (isTextField(el)) {
            tuneInput(el);
            // Center the field above the TV IME, which overlays rather than
            // resizing the WebView on many boxes. Repeat after the IME opens.
            scrollIntoViewSmart(el, true);
            window.setTimeout(function () {
                if (document.activeElement === el) {
                    scrollIntoViewSmart(el, true);
                }
            }, 350);
            requestKeyboard();
        } else {
            scrollIntoViewSmart(el);
        }
    }

    function onFocusOut(event) {
        var el = event.target;
        if (!el || el.nodeType !== 1) {
            return;
        }
        window.setTimeout(function () {
            var active = document.activeElement;
            if (active !== el && !(el.contains && el.contains(active))) {
                unmarkFocused(el);
            }
            if (isTextField(el) && !isTextField(active)) {
                bridgeCall('hideKeyboard');
            }
        }, 0);
    }

    function applyLayoutMode() {
        var path = location.pathname || '';
        var isLoginFlow = path.indexOf('/login/') === 0
                || path.indexOf('/merge-number') === 0
                || path.indexOf('/update-phone') === 0;
        var shortViewport = (window.innerHeight || document.documentElement.clientHeight) < 700;
        var html = document.documentElement;
        if (isLoginFlow && shortViewport) {
            html.classList.add('tv-compact-login');
        } else {
            html.classList.remove('tv-compact-login');
        }
    }

    function enhanceAll(force) {
        injectStyle();
        applyLayoutMode();
        tuneAllInputs();
        var nodes = document.querySelectorAll(SELECTOR);
        for (var i = 0; i < nodes.length; i++) {
            ensureTabIndex(nodes[i]);
        }
        cache = [];
        cacheAt = 0;
        if (force) {
            collect(true);
        }
    }

    function scheduleEnhance() {
        cache = [];
        cacheAt = 0;
        if (enhanceTimer) {
            return;
        }
        enhanceTimer = window.setTimeout(function () {
            enhanceTimer = null;
            enhanceAll(false);
        }, 150);
    }

    function startObserver() {
        if (observer || !document.body) {
            return;
        }
        observer = new MutationObserver(function (mutations) {
            for (var i = 0; i < mutations.length; i++) {
                if (mutations[i].addedNodes && mutations[i].addedNodes.length) {
                    scheduleEnhance();
                    return;
                }
            }
        });
        observer.observe(document.body, { childList: true, subtree: true });
    }

    function checkReady() {
        if (readySent) {
            return;
        }
        var root = document.getElementById('root');
        var hasContent = root ? root.children.length > 0 : document.body.children.length > 0;
        var hasControls = document.querySelector(
                'input:not([type="hidden"]), button, a[href], [class*="cursor-pointer"]');
        if (hasContent && hasControls) {
            readySent = true;
            window.setTimeout(function () {
                bridgeCall('onPageReady');
            }, 80);
            return;
        }
        readyAttempts++;
        if (readyAttempts > 40) {
            readySent = true;
            bridgeCall('onPageReady');
            return;
        }
        readyTimer = window.setTimeout(checkReady, 250);
    }

    function init() {
        injectStyle();
        enhanceAll(true);
        startObserver();

        document.addEventListener('keydown', onKeyDown, true);
        document.addEventListener('focusin', onFocusIn, true);
        document.addEventListener('focusout', onFocusOut, true);

        // Clicking/tapping a field is the other path that must summon the IME.
        document.addEventListener('click', function (event) {
            var el = event.target;
            if (isTextField(el)) {
                tuneInput(el);
                requestKeyboard();
            }
        }, true);

        document.addEventListener('DOMContentLoaded', function () {
            enhanceAll(true);
            startObserver();
        }, false);

        checkReady();
    }

    window.__melodifyTvEnhancer = {
        refresh: function () {
            enhanceAll(true);
        },
        focusFirst: function () {
            var list = collect(true);
            if (list.length) {
                focusElement(list[0]);
            }
        }
    };

    init();
})();
