'use strict';
// Diagnostic only. Observes this client's dealt hand; no network sends.
// Execute on the game's JS thread. Re-running restores the previous wrappers.
(function () {
    if (window.__jpqDealObserver) window.__jpqDealObserver.stop();
    var prototype = gm.shisanshui.SocketListener.prototype;
    var saved = [];
    var sequence = 0;
    function observe(name, collect) {
        var original = prototype[name];
        if (typeof original !== 'function') throw new Error('Missing listener: ' + name);
        var wrapper = function () {
            try { collect(arguments); } catch (_) { /* Never interrupt gameplay. */ }
            return original.apply(this, arguments);
        };
        saved.push({name: name, original: original, wrapper: wrapper});
        prototype[name] = wrapper;
    }
    function emit(data) {
        data.sequence = ++sequence;
        data.observedAtMs = Date.now();
        console.log('JPQ_LOCAL_DEAL ' + JSON.stringify(data));
    }
    observe('onDeal', function (args) {
        var data = args[1];
        if (!data || !Array.isArray(data.hand_cards)) return;
        emit({type: 'deal', gameRound: data.currentRound,
            cards: data.hand_cards.slice(), count: data.hand_cards.length});
    });
    observe('onSettleForRound', function () { emit({type: 'round-ended'}); });
    window.__jpqDealObserver = {stop: function () {
        saved.forEach(function (entry) {
            if (prototype[entry.name] === entry.wrapper) prototype[entry.name] = entry.original;
        });
        delete window.__jpqDealObserver;
    }};
    console.log('JPQ_LOCAL_DEAL observer-ready');
})();
