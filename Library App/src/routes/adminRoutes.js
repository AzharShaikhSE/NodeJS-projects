/*jslint node:true */
/*jslint es5:true */
'use strict';

var express = require('express');
var mongodb = require('mongodb').MongoClient;

var adminRouter = express.Router();

var books = [
    {
        title: 'The Lightning Thief',
        genre: 'Fantasy',
        author: 'Rick Riordan',
        bookId : 50,
        read: false
    },
    {
        title: 'The Sea of Monsters',
        genre: 'Fantasy',
        author: 'Azzy Rulz',
        bookId : 28186,
        read: false
            },
    {
        title: 'Sophie\'s World : The Greek Philosophers',
        genre: 'Fantasy',
        author: 'Jostein Gaarder',
        read: false
        }
];

var router = function (nav) {

    adminRouter.route('/addBooks')
        .get(function (req, res) {
            var url = 'mongodb://localhost:27017/libraryApp';
            mongodb.connect(url, function (err, db) {
                var collection = db.collection('books');
                collection.insertMany(books, function (err, results) {
                    res.send(results);
                    db.close();
                });
            });
           // res.send('Inserting Books');
        });

    return adminRouter;
};

module.exports = router;
