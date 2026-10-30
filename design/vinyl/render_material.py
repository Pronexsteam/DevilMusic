"""Original picture-disc master; transparent studio material for dynamic album art."""
import bpy, math, os
from mathutils import Vector
ROOT=os.path.dirname(os.path.abspath(__file__))
bpy.ops.object.select_all(action='SELECT');bpy.ops.object.delete(use_global=False)
s=bpy.context.scene
def material(name,color,metal,rough):
 m=bpy.data.materials.new(name);m.use_nodes=True;p=m.node_tree.nodes.get('Principled BSDF');p.inputs['Base Color'].default_value=(*color,1);p.inputs['Metallic'].default_value=metal;p.inputs['Roughness'].default_value=rough;return m,p
def surface(name,profile,mat):
 n=512;verts=[];faces=[]
 for r,z in profile:
  for i in range(n):
   a=2*math.pi*i/n;verts.append((r*math.cos(a),r*math.sin(a),z))
 for j in range(len(profile)-1):
  for i in range(n):faces.append((j*n+i,j*n+(i+1)%n,(j+1)*n+(i+1)%n,(j+1)*n+i))
 me=bpy.data.meshes.new(name);me.from_pydata(verts,[],faces);me.update();o=bpy.data.objects.new(name,me);bpy.context.collection.objects.link(o);me.materials.append(mat)
 for f in me.polygons:f.use_smooth=True
 return o
rim,p=material('Pressed graphite, rounded edge',(.018,.021,.025),.42,.29)
surface('Rounded record edge',[(1.82,-.009),(1.84,.001),(1.865,.009),(1.88,.012),(1.91,.007),(1.94,-.006),(1.957,-.028),(1.96,-.065)],rim)
hub,p=material('Recessed spindle and pressed shoulder',(.035,.040,.045),.38,.27)
surface('Spindle bevel',[(.075,-.018),(.082,.007),(.094,.013),(.111,.004)],hub)
# A physically lit transparent lacquer layer, with relief in the pressing.
m=bpy.data.materials.new('Clear lacquer over replaceable cover');m.use_nodes=True;nt=m.node_tree;nt.nodes.clear()
out=nt.nodes.new('ShaderNodeOutputMaterial');mix=nt.nodes.new('ShaderNodeMixShader');mix.inputs[0].default_value=.16
transparent=nt.nodes.new('ShaderNodeBsdfTransparent');gloss=nt.nodes.new('ShaderNodeBsdfGlossy');gloss.inputs['Roughness'].default_value=.23
tex=nt.nodes.new('ShaderNodeTexCoord');wave=nt.nodes.new('ShaderNodeTexWave');wave.wave_type='RINGS';wave.rings_direction='Z';wave.inputs['Scale'].default_value=48;wave.inputs['Distortion'].default_value=0
bump=nt.nodes.new('ShaderNodeBump');bump.inputs['Strength'].default_value=.2;bump.inputs['Distance'].default_value=.004
nt.links.new(tex.outputs['Object'],wave.inputs['Vector']);nt.links.new(wave.outputs['Color'],bump.inputs['Height']);nt.links.new(bump.outputs[0],gloss.inputs['Normal'])
nt.links.new(transparent.outputs[0],mix.inputs[1]);nt.links.new(gloss.outputs[0],mix.inputs[2]);nt.links.new(mix.outputs[0],out.inputs[0])
surface('Continuous pressed lacquer',[(.11,.004),(.17,.002),(.25,.002),(.29,.010),(.32,.011),(.35,.001),(.39,-.007),(1.60,-.007),(1.76,-.004),(1.82,-.009)],m)
for i in range(18):
 r=1.84+i*.0052
 surface('Outer cut %02d'%i,[(r-.0009,.002),(r,.004),(r+.0009,.002)],rim)
def aim(o,target=(0,0,0)):o.rotation_euler=(Vector(target)-o.location).to_track_quat('-Z','Y').to_euler()
def light(name,pos,power,size,sy):
 d=bpy.data.lights.new(name,'AREA');d.energy=power;d.shape='RECTANGLE';d.size=size;d.size_y=sy;o=bpy.data.objects.new(name,d);bpy.context.collection.objects.link(o);o.location=pos;aim(o)
light('Broad silk',(-2,3,5),380,5,3)
light('Lower softbox',(2,-3,4),110,4,2)
light('Edge card',(-4,-.2,2),80,.4,3)
s.world.use_nodes=True;s.world.node_tree.nodes['Background'].inputs[0].default_value=(.13,.14,.15,1);s.world.node_tree.nodes['Background'].inputs[1].default_value=.25
bpy.ops.object.camera_add(location=(0,0,8));cam=bpy.context.object;aim(cam);cam.data.type='ORTHO';cam.data.ortho_scale=4.1;s.camera=cam
s.render.engine='CYCLES';s.cycles.samples=96;s.cycles.use_denoising=True
try:
 p=bpy.context.preferences.addons['cycles'].preferences;p.compute_device_type='OPTIX';p.get_devices()
 for d in p.devices:d.use=d.type=='OPTIX'
 s.cycles.device='GPU'
except Exception:pass
s.render.film_transparent=True;s.render.image_settings.file_format='PNG';s.render.image_settings.color_mode='RGBA';s.render.resolution_x=768;s.render.resolution_y=768;s.render.resolution_percentage=100;s.view_settings.view_transform='AgX'
bpy.ops.wm.save_as_mainfile(filepath=os.path.join(ROOT,'Devil Music - pressed vinyl.blend'))
s.render.filepath=os.path.join(ROOT,'vinyl-material.png');bpy.ops.render.render(write_still=True)
