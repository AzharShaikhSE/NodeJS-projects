/*jslint node:true */
/*jslint es5:true */
'use strict';

var express = require('express');

var authorRouter = express.Router();

var router = function (nav) {

    var authors = [
        {
            name: 'Rick Riordan',
            email: 'RickRiordan@gmail.com',
            url: 'http://www.RickRiordan/contact',
            read: false
    },
        {
            name: 'John Smith',
            email: 'JohnSmith@gmail.com',
            url: 'http://www.JohnSmith/contact',
            read: false
            },
        {
            name: 'Jostein Gaarder',
            email: 'JosteinGaarder@gmail.com',
            url: 'http://www.JosteinGaarder/contact',
            read: false
        }
];

    authorRouter.use(function(req, res, next) {
        if (!req.user) {
                res.redirect('/');
            }
            next();
    });
    authorRouter.route('/')
        .get(function (req, res) {
            res.render('authorListView', {
                title: 'Authors',
                nav: nav,
                authors: authors
            });
        });

    authorRouter.route('/:id')
        .get(function (req, res) {
            var id = req.params.id;
            res.render('authorView', {
                title: 'Authors',
                nav: nav,
                author: authors[id]
            });
        });

    return authorRouter;
};
module.exports = router;
