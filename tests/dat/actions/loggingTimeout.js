// Licensed to the Apache Software Foundation (ASF) under one or more contributor
// license agreements; and to You under the Apache License, Version 2.0.

// This action prints log lines for a defined duration.
// The output is throttled by a defined delay between two log lines
// in order to keep the log size small and to stay within the log size limit.

function getArg(value, defaultValue) {
   return value ? value : defaultValue;
}

// input: { duration: <duration in millis>, delay: <delay in millis> }, e.g.
// main( { delay: 100, duration: 10000 } );
function main(args) {

   var durationMillis = getArg(args.duration, 120000);
   var delayMillis = getArg(args.delay, 100);

   var logLines = 0;
   var startMillis = new Date();

   var timeout = setInterval(function() {
      console.log(`[${ ++logLines }] The quick brown fox jumps over the lazy dog.`);
   }, delayMillis);

   return new Promise(function(resolve, reject) {
      setTimeout(function() {
         clearInterval(timeout);
         var message = `hello, I'm back after ${new Date() - startMillis} ms and printed ${logLines} log lines`
         console.log(message)
         resolve({ message: message });
      }, durationMillis);
   });

}

