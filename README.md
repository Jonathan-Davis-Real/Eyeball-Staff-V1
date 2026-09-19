This is a V1 version of the design. The concept is a magical eyeball that sits on top of a staff that follows people with it's gaze.
I developed this using an esp32 camera board streaming the camera feed to an android app. 
The image processing is done on the android side while the esp32 handles moving the servo.
The esp32 camera is pretty low resolution and has a limited framerate, I plan to switch to raspberry pi with a higher quality
external camera but this works as a proof of concept. 
