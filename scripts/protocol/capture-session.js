'use strict';
// Game connection only. No calls which send messages or control gameplay.
(function () {
    if (window.__jpqSessionCapture) throw new Error('Capture already installed');
    var saved = [], sequence = 0, session = String(Date.now());
    function clean(key, value) {
        if (/token|password|sessionkey|authorization|cookie|secret/i.test(key)) return '[REDACTED]';
        if (typeof value === 'function') return '[FUNCTION]';
        return value;
    }
    function emit(value) {
        value.timeMs = Date.now(); value.sequence = ++sequence; value.session = session;
        var text = JSON.stringify(value, clean), size = 600;
        for (var i = 0; i < text.length; i += size)
            console.log('JPQ_CAPTURE ' + session + ' ' + sequence + ' ' + i + ' ' + text.length + ' ' + text.slice(i, i + size));
    }
    function hex(value) {
        if (!value) return null;
        var bytes = value instanceof ArrayBuffer ? new Uint8Array(value) : value;
        if (typeof bytes.length !== 'number') return null;
        var out = '';
        for (var i = 0; i < bytes.length; i++) out += ('0' + (bytes[i] & 255).toString(16)).slice(-2);
        return out;
    }
    function isGame(socket) {
        return socket && !socket._isHallSocket && !socket._isHallWebSocket &&
            socket._commonReader instanceof gm.shisanshui.SocketReader;
    }
    function wrap(object, name, before, after) {
        var original = object[name];
        if (typeof original !== 'function') throw new Error('Missing method ' + name);
        var own = Object.prototype.hasOwnProperty.call(object, name);
        var replacement = function () {
            try { if (before) before.call(this, arguments); } catch (e) { emit({type: 'capture-error', method: name, message: String(e)}); }
            var result = original.apply(this, arguments);
            try { if (after) after.call(this, arguments, result); } catch (e) { emit({type: 'capture-error', method: name, message: String(e)}); }
            return result;
        };
        saved.push(function () { if (object[name] === replacement) { if (own) object[name] = original; else delete object[name]; } });
        object[name] = replacement;
    }
    wrap(socketBase.prototype, 'writePacket', function (args) {
        if (isGame(this)) emit({type: 'outgoing-fields', command: args[1], data: args[0]});
    }, function (args, result) {
        if (isGame(this)) emit({type: 'outgoing-protobuf', command: args[1], hex: result && result.serializeBinary ? hex(result.serializeBinary()) : null});
    });
    wrap(socketBase.prototype, 'readPacket', null, function (args, result) {
        if (isGame(this)) emit({type: 'incoming-packet', command: result.cmd,
            transportSequence: args[0].seq, data: result.data, protobufHex: hex(args[0].data)});
    });
    ['onOpen', 'onClose', 'onError'].forEach(function (name) {
        wrap(GameSocket.prototype, name, function () { if (isGame(this)) emit({type: 'connection', event: name}); });
    });
    window.__jpqSessionCapture = {stop: function () {
        emit({type: 'capture-stopped'}); saved.reverse().forEach(function (restore) { restore(); });
        delete window.__jpqSessionCapture;
    }};
    emit({type: 'capture-ready', commands: shisanshui.Command, scope: 'shisanshui-game-connection'});
})();
