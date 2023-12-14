const exec = require('cordova/exec'),
    SimpleCamera = {};

SimpleCamera.takePhoto = function (success, error, options) {
    options = options || {};

    exec(success, error, 'SimpleCamera', 'takePhoto', [
        options.quality || 75,
        options.width || 1024,
        options.height || 1024,
    ]);
};

SimpleCamera.deletePhoto = function (success, error, path) {
    exec(success, error, 'SimpleCamera', 'deletePhoto', [path]);
};

module.exports = SimpleCamera;
