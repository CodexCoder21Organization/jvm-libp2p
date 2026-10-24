from pathlib import Path
import subprocess,json
root=Path.cwd();evidence=root.parent.parent.parent/'out'/'F71c-evidence'
paths=subprocess.check_output(['git','diff','--name-only','origin/develop'],text=True).splitlines()
kt=[str(root/p) for p in paths if p.endswith('.kt')];java=[str(root/p) for p in paths if p.endswith('.java')]
script='''gradle.projectsEvaluated {
 def p=gradle.rootProject.project(':libp2p')
 p.spotless {
  kotlin { target(p.files(KOTLIN_FILES)) }
  java { target(p.files(JAVA_FILES)) }
 }
 ['spotlessKotlin','spotlessJava'].each { name ->
  p.tasks.named(name).configure {
   doFirst {
    def sourceInputs=inputs.files.files.findAll { it.path.endsWith('.kt') || it.path.endsWith('.java') }
    new File('INPUT_DIRECTORY/'+name+'-inputs.txt').text=sourceInputs.collect { it.path }.sort().join('\\n')
    println('F71C_FORMAT_INPUTS_'+name+'='+sourceInputs.size())
   }
  }
 }
}
'''.replace('KOTLIN_FILES',json.dumps(kt)).replace('JAVA_FILES',json.dumps(java)).replace('INPUT_DIRECTORY',str(evidence))
(evidence/'format-scope.gradle').write_text(script);print('Kotlin',len(kt),'Java',len(java))
