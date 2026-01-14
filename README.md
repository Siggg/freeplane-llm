# freeplane-llm
A groovy script addon to Freeplane which integrates LLM capabilities right into your mindmap.

# Installation
- Copy-paste or download the groovy script into the freeplane default scripts folder (e.g. on Windows C:\Users\<username>\.freeplane\1.12.x\scripts\)
- Review the code or ask a skillful friend or chabot about the risks caused by the code of such a script
- Set your OpenAI API Key as a global environment variable named OPENAI_KEY
- Enable the execution of scripts in Freeplane (look for the Tools menu then Preferences, then Extensions, then Scripts, then set the script execution switch to YES)
- Give the required permission to Freeplane scripts (still in Tools/Preferences/Extension/Scripts, tick the permissions for reading and writing files, for using the network and for executing external commands ; note that this may be dangerous)
- Set a new shortcut for executing the script (Tools menu, then create a new keyboard shortcut, then press the intended keys, then go to Tools/Scripts and click on the script)
That should be it.

# Use
- Create a new node in your mindmap
- write "@help" as the content of your node
- execute the script from this node
- the script will insert help instructions into your mindmap
- follow these instructions
