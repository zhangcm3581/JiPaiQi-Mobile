'use strict';
// Passive inventory for the explicitly selected game process. No gameplay calls.
for (const m of Process.enumerateModules()) {
 if (!m.name.includes('cocos')) continue;
 console.log(JSON.stringify({module:m.name,exports:m.enumerateExports().filter(e=>/xxtea|evalString|onMessage|WebSocket.*send|setXXTEA/i.test(e.name)).map(e=>({name:e.name,address:e.address.toString(),type:e.type}))}));
}
