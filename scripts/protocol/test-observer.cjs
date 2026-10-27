const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const source=fs.readFileSync('app/src/main/assets/protocol-observer.js','utf8').replace('__PREFIX__',JSON.stringify('TEST '));
const packets=JSON.parse(fs.readFileSync('app/src/androidTest/assets/protocol-own-replay.json'));
const events=[];function Reader(){}function Socket(){}Socket.prototype.readPacket=function(packet){return packet};
const original=Socket.prototype.readPacket;
const context={window:{},socketBase:Socket,gm:{shisanshui:{SocketReader:Reader}},console:{log(text){events.push(JSON.parse(text.slice(5)))}},Date};
vm.runInNewContext(source,context);
const socket=new Socket();socket._commonReader=new Reader();
for(const packet of packets)assert.equal(socket.readPacket(packet),packet);
assert.equal(events.filter(x=>x.event==='deal').length,8);
assert.equal(events.filter(x=>x.event==='compare').length,8);
assert.equal(events.filter(x=>x.event==='end').length,8);
assert.equal(events.filter(x=>x.event==='settled').length,1);
for(let i=0;i<8;i++){const deal=events.filter(x=>x.event==='deal')[i];assert.ok(deal.game_key.endsWith(`:${i+1}`));assert.equal(deal.cards.length,13);}
const count=events.length;socket._isHallSocket=true;for(const p of packets)socket.readPacket(p);assert.equal(events.length,count);
context.window.__jpqLiveBridge.stop();assert.equal(Socket.prototype.readPacket,original);
console.log('PASS: saved 8-round own-hand replay; 8 comparisons; 8 ends; 1 settlement; hall isolation; clean detach');
// Restart inside a room: no login/room packet is replayed by the game.
socket._isHallSocket=false;
vm.runInNewContext(source,context);
const fresh={cmd:21,data:{currentRound:9,hand_cards:[36,71,27,45,34,68,71,36,26,50,42,38,66]}};
socket.readPacket(fresh);
assert.equal(events.at(-1).event,'deal','fresh hand must be emitted without room context');
const localKey=events.at(-1).game_key;
socket.readPacket({cmd:2,data:{code:1,roomInfo:{roomId:'different-room'}}});
socket.readPacket(fresh);assert.equal(events.at(-1).game_key,localKey,'room metadata must not change duplicate identity');
assert.ok(localKey);
vm.runInNewContext(source,context);
socket.readPacket(fresh);
assert.equal(events.at(-1).game_key,localKey,'restart must preserve duplicate identity');
for(const [cmd,event] of [[23,'compare'],[29,'end'],[30,'settled']]){
 socket.readPacket({cmd,data:{}});assert.equal(events.at(-1).event,event);assert.equal(events.at(-1).game_key,localKey);
}
socket.readPacket({cmd:21,data:{hand_cards:fresh.data.hand_cards}});
assert.equal(events.at(-1).event,'deal','cards alone do not require room or game round');
context.window.__jpqLiveBridge.stop();
console.log('PASS: missing-room deal; collector reinjection; phase linkage; missing round');
