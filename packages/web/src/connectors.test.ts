import { expect,it } from 'vitest'
import { attach,beginArrow,dragArrow,resolveConnectors } from './connectors'
import { translated } from './selection'
import { CrdtDocument } from './sync/CrdtDocument'
import type { BoardElement } from './board'
const target:BoardElement={id:'target',type:'rectangle',color:'#123456',width:3,points:[{x:100,y:100},{x:200,y:200}]}
const arrow:BoardElement={id:'arrow',type:'arrow',color:'#123456',width:3,points:[{x:200,y:150},{x:300,y:150}],startAttachment:{id:'target',x:1,y:.5}}
it('follows targets without writing connector state and restores after target undo',()=>{
 const a=new CrdtDocument(),b=new CrdtDocument();a.put(target);a.put(arrow);b.receive(a.fullState())
 a.move(['target'],{x:50,y:20});b.receive(a.fullState())
 expect(b.getElements().find(e=>e.id==='arrow')).toEqual(arrow)
 expect(resolveConnectors(b.getElements()).find(e=>e.id==='arrow')?.points[0]).toEqual({x:250,y:170})
 a.remove(['target']); expect(resolveConnectors(a.getElements())).toEqual([])
 a.undo();expect(resolveConnectors(a.getElements())).toHaveLength(2)
})
it('endpoint editing reconnects and detaches, while group moves do not double move attached ends',()=>{
 const gesture=beginArrow([target,arrow],{x:200,y:150},10,{...arrow,id:'unused'})
 expect(gesture.existing).toBe(true);expect(gesture.index).toBe(0)
 const detached=dragArrow(gesture.element,0,{x:400,y:400},[target],10)
 expect(detached.startAttachment).toBeUndefined()
 expect(attach([target],{x:200,y:150},10)).toEqual(arrow.startAttachment)
 const moved=translated([target,arrow],['target','arrow'],{x:50,y:20})
 expect(resolveConnectors(moved).find(e=>e.id==='arrow')?.points).toEqual([{x:250,y:170},{x:350,y:170}])
})
it('ellipse anchors lie on its boundary and missing or wrong-type targets cannot recurse',()=>{
 const a=attach([{...target,type:'ellipse'}],{x:200,y:150},10)!
 expect(a.x).toBe(1);expect(a.y).toBe(.5)
 expect(resolveConnectors([arrow])).toEqual([])
 expect(resolveConnectors([{...arrow,startAttachment:{id:'arrow',x:0,y:0}}])).toEqual([])
})

it('unattached arrows omit optional attachment fields on the wire',()=>{
 const fresh={...arrow};delete fresh.startAttachment
 expect(beginArrow([],{x:500,y:500},10,fresh).element).not.toHaveProperty('startAttachment')
})
