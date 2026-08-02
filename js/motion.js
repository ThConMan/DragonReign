/* ===========================================================================
 * Shared motion layer for the plugin docs sites.
 *
 * Deliberately markup-free: it finds the framework's own components and tags
 * them at runtime, so dropping this file plus one <script> line into a site is
 * the whole integration. Pairs with the MOTION & DEPTH LAYER block in
 * css/style.css.
 *
 * Everything checks prefers-reduced-motion and no-ops rather than degrading
 * into a half-animated page.
 * ======================================================================== */
(function () {
    'use strict';

    var reduce = window.matchMedia &&
                 window.matchMedia('(prefers-reduced-motion: reduce)').matches;

    function each(sel, root, fn) {
        var nodes = (root || document).querySelectorAll(sel);
        for (var i = 0; i < nodes.length; i++) fn(nodes[i], i);
    }

    /* ── Scroll progress + nav elevation ─────────────────────────────────── */
    function scrollChrome() {
        var nav = document.querySelector('.nav');
        var bar = null;

        if (!reduce) {
            bar = document.createElement('div');
            bar.className = 'scroll-progress';
            bar.setAttribute('aria-hidden', 'true');
            document.body.appendChild(bar);
        }
        if (!nav && !bar) return;

        var ticking = false;
        function update() {
            if (ticking) return;
            ticking = true;
            requestAnimationFrame(function () {
                if (bar) {
                    var max = document.documentElement.scrollHeight - window.innerHeight;
                    bar.style.setProperty('--p', max > 0 ? window.scrollY / max : 0);
                }
                if (nav) nav.classList.toggle('is-stuck', window.scrollY > 12);
                ticking = false;
            });
        }
        window.addEventListener('scroll', update, { passive: true });
        update();
    }

    /* ── Reveal on scroll ────────────────────────────────────────────────
     * Reuses the framework's existing .fade-in / .visible contract, which was
     * defined in style.css but never actually applied to any markup.
     * ------------------------------------------------------------------- */
    var REVEAL = [
        '.section-title',
        '.section-subtitle',
        '.cta-card',
        '.docs-section',
        '.mc-banner'
    ];
    var STAGGER = [
        '.features-grid',
        '.steps-grid',
        '.compat-grid',
        '.plugins-grid',
        '.docs-grid',
        '.hero-buttons',
        '.plugin-hero-meta'
    ];

    function markReveal(el, delayIndex) {
        if (el.classList.contains('fade-in')) return;
        el.classList.add('fade-in');
        if (delayIndex) el.setAttribute('data-d', Math.min(delayIndex, 5));
    }

    function collectTargets() {
        REVEAL.forEach(function (sel) { each(sel, document, function (el) { markReveal(el, 0); }); });
        STAGGER.forEach(function (sel) {
            each(sel, document, function (grid) {
                each(':scope > *', grid, function (child, i) { markReveal(child, i + 1); });
            });
        });
    }

    function reveal() {
        collectTargets();

        // No observer, or motion is unwanted: show everything and stop.
        if (reduce || !('IntersectionObserver' in window)) {
            each('.fade-in', document, function (el) { el.classList.add('visible'); });
            return;
        }

        var io = new IntersectionObserver(function (entries) {
            entries.forEach(function (e) {
                if (!e.isIntersecting) return;
                show(e.target);
                io.unobserve(e.target);
            });
        }, { rootMargin: '0px 0px -10% 0px', threshold: 0.12 });

        each('.fade-in', document, function (el) { io.observe(el); });

        /* Failsafe. This is a documentation site, so content must never end up
         * permanently invisible because a decorative animation did not run --
         * which is exactly what happens if the observer never fires (a context
         * that is not compositing, a background tab that never paints, an
         * embedded webview). Anything still hidden after a beat is shown
         * outright. Elements already revealed by the observer are untouched. */
        setTimeout(function () {
            each('.fade-in:not(.visible)', document, function (el) {
                // Hard reveal: .visible alone only sets the transition's end
                // state, and in a context that never paints the transition
                // never advances either -- so the element would stay at
                // opacity 0. .fade-in-now drops the transition entirely.
                el.classList.add('fade-in-now');
                show(el);
            });
        }, 1600);
    }

    function show(el) {
        el.classList.add('visible');
        each('[data-count]', el, countUp);
    }

    /* ── Count-up for any numeric stat that opts in with data-count ──────── */
    function countUp(el) {
        var target = parseFloat(el.getAttribute('data-count'));
        if (isNaN(target)) return;
        var suffix = el.getAttribute('data-suffix') || '';
        if (reduce || !target) { el.textContent = target + suffix; return; }

        var start = performance.now(), dur = 1000;
        requestAnimationFrame(function step(now) {
            var t = Math.min((now - start) / dur, 1);
            var v = target * (1 - Math.pow(1 - t, 3));
            el.textContent = (target % 1 ? v.toFixed(1) : Math.round(v)) + suffix;
            if (t < 1) requestAnimationFrame(step);
        });
    }

    /* ── Docs sidebar scroll-spy ─────────────────────────────────────────── */
    function scrollSpy() {
        var links = document.querySelectorAll('.docs-toc a[href^="#"]');
        if (!links.length || !('IntersectionObserver' in window)) return;

        var map = {};
        var targets = [];
        for (var i = 0; i < links.length; i++) {
            var id = links[i].getAttribute('href').slice(1);
            var section = document.getElementById(id);
            if (!section) continue;
            map[id] = links[i];
            targets.push(section);
        }
        if (!targets.length) return;

        var visible = {};
        var spy = new IntersectionObserver(function (entries) {
            entries.forEach(function (e) { visible[e.target.id] = e.isIntersecting; });

            // Highlight the topmost section currently on screen.
            var current = null;
            for (var j = 0; j < targets.length; j++) {
                if (visible[targets[j].id]) { current = targets[j].id; break; }
            }
            for (var id in map) {
                if (Object.prototype.hasOwnProperty.call(map, id)) {
                    map[id].classList.toggle('is-current', id === current);
                }
            }
        }, { rootMargin: '-72px 0px -55% 0px', threshold: 0 });

        targets.forEach(function (t) { spy.observe(t); });
    }

    function init() {
        scrollChrome();
        reveal();
        scrollSpy();
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
