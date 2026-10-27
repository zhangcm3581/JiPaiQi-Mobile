(function () {
    if (window.__jpqLiveBridge) window.__jpqLiveBridge.stop();
    var room='',round=0,key='',original=socketBase.prototype.readPacket;
    var prefix=__PREFIX__;
    function emit(event,cards){console.log(prefix+JSON.stringify({event:event,game_key:key,cards:cards||[],observed_at_ms:Date.now()}));}
    var replacement=function(){
        var result=original.apply(this,arguments);
        try {
            if(this._isHallSocket||this._isHallWebSocket||!(this._commonReader instanceof gm.shisanshui.SocketReader))return result;
            var d=result.data||{},cmd=result.cmd;
            if(cmd===2&&d.code===1&&d.roomInfo){room=String(d.roomInfo.roomId);round=0;key='';}
            if(cmd===21){
                if(Array.isArray(d.hand_cards)){
                    round=d.currentRound>0?d.currentRound:1;
                    // Local duplicate identity only. Never sent as the server's room key.
                    key='hand_'+d.hand_cards.slice().sort(function(a,b){return a-b;}).join('_')+':'+round;
                    emit('deal',d.hand_cards.slice());
                }else emit('invalid-hand');
            }
            if(key&&cmd===23)emit('compare');
            if(key&&cmd===29)emit('end');
            if(key&&cmd===30)emit('settled');
        }catch(error){emit('observer-error');}
        return result;
    };
    socketBase.prototype.readPacket=replacement;
    window.__jpqLiveBridge={token:prefix,health:function(){emit(socketBase.prototype.readPacket===replacement?"heartbeat":"observer-lost");},stop:function(){if(socketBase.prototype.readPacket===replacement)socketBase.prototype.readPacket=original;delete window.__jpqLiveBridge;}};
    emit('ready');
})();
