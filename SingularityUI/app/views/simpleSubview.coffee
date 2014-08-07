View = require './view'

# You feed it a collection/model & template and it listens to it and renders
# when appropriate
#
#   myView = new SimpleSubview {collection, template}
#   @$('#my-container').html myView.$el
#
# And it does everything for you, just do stuff with the collection
class SimpleSubview extends View

    initialize: ({@template}) ->
        @data = if @collection? then @collection else @model

        for eventName in ['sync', 'add', 'remove', 'change']
            @listenTo @data, eventName, @render
            
        @listenTo @data, 'reset', =>
            @$el.empty()

    render: ->
        return if not @data.synced and @data.isEmpty?()
        
        @$el.html @template
            data:   @data.toJSON()
            synced: @data.synced

        utils.setupCopyLinks @$el if @$('.horizontal-description-list').length

module.exports = SimpleSubview