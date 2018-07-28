# beat-dropper

A program for manipulating audio beats, or just time sections in general.
It allows you to select portions of audio to drop, in certain pre-determined ways.

Takes input in a variety of audio formats, such as `mp3`, `aiff`, and `wav`.
However, output is wav-only.

### Intro
Running `beat-dropper` without any arguments will give you information on how to use it:
```bash
$ beat-dropper
usage: beat-dropper <dropper> [dropper options] <file>

For dropper options, see beat-dropper [dropper] --help.

<dropper> may be any one of the following:
	[see your installation]
```

One of the included droppers is the `identity` dropper, which will simply output the raw audio.
You can try it like so:
```bash
$ beat-dropper identity [your file]
```
You should then see a new file appear. If, for example, you gave it `All Star.mp3`, 
you would have a file named `All Star [Identity].wav`. This name includes info on what was done
to the file in the brackets.
