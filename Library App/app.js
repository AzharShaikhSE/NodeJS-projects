/*jslint node:true */
/*jslint es5:true */
'use strict';

var express = require('express');
var bodyParser = require('body-parser');
var cookieParser = require('cookie-parser');
var passport = require('passport');
var session = require('express-session');

var app = express();

var port = process.env.PORT || 5000;

var nav = [{
    Link: '/Books',
    Text: 'Book'
 }, {
    Link: '/Authors',
    Text: 'Author'
            }];

var bookRouter = require('./src/routes/bookRoutes.js')(nav);
var authorRouter = require('./src/routes/authorRoutes.js')(nav);
var adminRouter = require('./src/routes/adminRoutes.js')(nav);
var authRouter = require('./src/routes/authRoutes.js')(nav);

app.use(express.static('public'));
app.use(bodyParser.json());
app.use(bodyParser.urlencoded());
app.use(cookieParser());
app.use(session({ secret : 'Library'}));


require('./src/config/passport.js')(app);

app.set('views', './src/views');
app.set('view engine', 'ejs');

app.use('/Books', bookRouter);
app.use('/Authors', authorRouter);
app.use('/Admin', adminRouter);
app.use('/Auth', authRouter);

app.get('/', function (req, res) {
    res.render('index', {
        title: 'My App',
        nav: nav
    });
});

app.listen(port, function (err) {
    console.log('Running server on port: ' + port);
});
