/*jslint node:true */
/*jslint es5:true */
'use strict';

var gulp = require('gulp'),
    jshint = require('gulp-jshint'),
    jscs = require('gulp-jscs'),
    nodemon = require('gulp-nodemon');

var jsFiles = ['*.js', 'src/**/*.js'];

gulp.task('style', function () {
    return gulp.src(jsFiles)
        .pipe(jshint())
        .pipe(jshint.reporter('jshint-stylish', {
            verbose: true
        }))
        .pipe(jscs());

});

gulp.task('inject', function () {
    var wiredep = require('wiredep').stream,
        inject = require('gulp-inject'),
        injectSrc = gulp.src(['./public/css/*.css',
                              './public/js/*.js'], {
            read: false
        }),
        injectOptions = {
            ignorePath: '/public'
        },
        options = {
            bowerJson: require('./bower.json'),
            directory: './public/lib',
            ignorePath: '../../public'
        };

    return gulp.src('./src/views/*.jade')
        .pipe(wiredep(options))
        .pipe(inject(injectSrc, injectOptions))
        .pipe(gulp.dest('./src/views/'));
});

gulp.task('serve', ['style', 'inject'], function () {
    var options = {
        script: 'app.js',
        delayTime: 1,
        env: {
            'PORT': 8080
        },
        watch: jsFiles
    };
    return nodemon(options)
        .on('restart', function (ev) {
            console.log('Restarting the server ...');
        });
});
